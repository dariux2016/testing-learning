package com.example.testinglearning.order;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Full integration tests for shipping an order through a WireMock carrier (see
 * docs/testing-strategy.md §7).
 *
 * <p>Every piece has already been tested on its own: {@link ShippingClientSliceTest} (HTTP
 * handling), {@link ResilientShippingClientUnitTest} (retry/circuit logic, with hand-built
 * configs), {@link OrderServiceUnitTest} and {@link OrderControllerSliceTest}. These two tests prove
 * what none of those can:
 * <ul>
 *   <li>the {@code resilience4j.*.instances.shipping.*} properties really bind onto the registries
 *       {@link ResilientShippingClient} uses (max-attempts=3 shows up as exactly 3 requests on the
 *       wire);</li>
 *   <li>a carrier outage travels all the way up to our 503 {@code ProblemDetail}, and the real
 *       database still holds the order as PAID.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
class OrderShippingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @RegisterExtension
    static final WireMockExtension carrier = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void carrierUrl(DynamicPropertyRegistry registry) {
        registry.add("shipping.client.base-url", carrier::baseUrl);
    }

    @Autowired
    private RestTestClient client;

    // Security has its own tests (OrderControllerSecuritySliceTest,
    // OrderSecurityKeycloakIntegrationTest). Here a mocked JwtDecoder accepts one fixed token as a
    // STAFF user. The real filter chain and JWT-to-authentication conversion still run, without a
    // Keycloak container.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void authenticateAsStaff() {
        when(jwtDecoder.decode("staff-token")).thenReturn(Jwt.withTokenValue("staff-token")
                .header("alg", "none")
                .subject("staff-1")
                .claim("email", "sam@example.com")
                .claim("realm_access", Map.of("roles", List.of("STAFF")))
                .build());
        client = client.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer staff-token").build();
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetCircuitBreaker() {
        // The Spring context (and so the circuit breaker's state) is cached and shared across test
        // methods, but WireMock's stubs are reset between them. Without this reset, the failures
        // recorded by one test could leave the circuit open for the next, and the result would
        // depend on test order.
        circuitBreakerRegistry.circuitBreaker(ResilientShippingClient.INSTANCE).reset();
    }

    @Test
    void ship_whenCarrierFailsTwiceThenRecovers_retriesAndShipsWithTrackingNumber() {
        // A WireMock scenario is a small state machine: each request moves it to the next state,
        // so the same URL answers 503, then 503, then 201.
        carrier.stubFor(post("/shipments").inScenario("flaky carrier")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("failed once"));
        carrier.stubFor(post("/shipments").inScenario("flaky carrier")
                .whenScenarioStateIs("failed once")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("failed twice"));
        carrier.stubFor(post("/shipments").inScenario("flaky carrier")
                .whenScenarioStateIs("failed twice")
                .willReturn(okJson("""
                        { "trackingNumber": "TRACK-RETRIED" }
                        """).withStatus(201)));

        Long orderId = placeAndPayOrder("ORD-SHIP-RETRY");

        client.post().uri("/api/orders/{id}/ship", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SHIPPED")
                .jsonPath("$.trackingNumber").isEqualTo("TRACK-RETRIED");

        // All three attempts carry the same idempotency key, which is what makes it safe for the
        // carrier to see the same POST more than once.
        carrier.verify(3, postRequestedFor(urlEqualTo("/shipments"))
                .withHeader(ShippingClient.IDEMPOTENCY_KEY_HEADER, equalTo("ORD-SHIP-RETRY")));

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(persisted.getTrackingNumber()).isEqualTo("TRACK-RETRIED");
    }

    @Test
    void ship_whenCarrierStaysDown_returns503AndOrderStaysPaid() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(503)));

        Long orderId = placeAndPayOrder("ORD-SHIP-DOWN");

        client.post().uri("/api/orders/{id}/ship", orderId)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Shipping carrier unavailable")
                .jsonPath("$.detail").isEqualTo("Shipping carrier answered HTTP 503 for order 'ORD-SHIP-DOWN'");

        // max-attempts=3 from application.properties, observed on the wire.
        carrier.verify(3, postRequestedFor(urlEqualTo("/shipments")));

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(persisted.getTrackingNumber()).isNull();
    }

    private Long placeAndPayOrder(String orderNumber) {
        OrderResponse created = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "%s",
                          "customerEmail": "alice@example.com",
                          "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
                        }
                        """.formatted(orderNumber))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        client.post().uri("/api/orders/{id}/pay", created.id())
                .exchange()
                .expectStatus().isOk();

        return created.id();
    }
}
