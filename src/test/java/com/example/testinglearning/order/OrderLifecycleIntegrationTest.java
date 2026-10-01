package com.example.testinglearning.order;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Full integration tests for the order feature (see docs/testing-strategy.md §5).
 *
 * <p>Everything below the HTTP boundary is real: the actual {@link OrderController}, the actual
 * {@link OrderService}, the actual {@link OrderRepository} against a real
 * {@code postgres:16-alpine} Testcontainer, and {@link RestTestClient} bound to the actual running
 * server on a random port (not MockMvc) — so requests really travel over HTTP and responses are
 * really deserialized from the wire. This is the only tier that proves the whole chain wired
 * together; every layer was already covered in isolation by {@link OrderRepositorySliceTest},
 * {@link OrderServiceUnitTest}, and {@link OrderControllerSliceTest}, each with the collaborator
 * below it mocked away. Kept to two tests per §5 — this tier is the slowest, and only worth it for
 * what the mocked-out slices can't prove for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
// Unlike @DataJpaTest, @SpringBootTest doesn't wrap the test method in a transaction by default —
// each HTTP call runs its own, on the server's own thread. Adding it here just for the test's own
// repository reads below is safe: it opens a session on the test thread only, after those HTTP
// calls have already committed on their own connections, so it sees their data and rolls back only
// the test's own read-side work at the end.
@Transactional
class OrderLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // placeOrder() now publishes an OrderPlacedEvent (see OrderEventPublisher); a real broker here
    // keeps that a normal, successful send instead of connection-refused noise on every test run.
    // The event's own shape/headers are pinned by OrderEventPublisherIntegrationTest, not here.
    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    // ship() now books the parcel with the external carrier (see ShippingClient). A happy-path
    // WireMock stub keeps this test about the order lifecycle. The carrier's failure modes are
    // covered by ShippingClientSliceTest and OrderShippingIntegrationTest.
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

    @Test
    void placeOrder_thenPay_thenShip_persistsEachTransitionInTheRealDatabase() {
        OrderResponse created = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-LIFECYCLE-1",
                          "customerEmail": "alice@example.com",
                          "items": [ { "productName": "Widget", "quantity": 2, "unitPrice": 9.99 } ]
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        Long orderId = created.id();

        client.post().uri("/api/orders/{id}/pay", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PAID");

        carrier.stubFor(post("/shipments").willReturn(okJson("""
                { "trackingNumber": "TRACK-LIFECYCLE-1" }
                """).withStatus(201)));

        client.post().uri("/api/orders/{id}/ship", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SHIPPED")
                .jsonPath("$.trackingNumber").isEqualTo("TRACK-LIFECYCLE-1");

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(persisted.getTrackingNumber()).isEqualTo("TRACK-LIFECYCLE-1");
        assertThat(persisted.getItems()).extracting(OrderItem::getProductName).containsExactly("Widget");
    }

    @Test
    void placeOrder_withDuplicateOrderNumber_returns409FromARealConstraintViolation() {
        String body = """
                {
                  "orderNumber": "ORD-LIFECYCLE-DUP",
                  "customerEmail": "alice@example.com",
                  "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
                }
                """;

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated();

        // Second insert with the same order number hits the real unique constraint on the real
        // database — nothing here is mocked, unlike every other tier's version of this scenario.
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Duplicate order number")
                .jsonPath("$.detail").isEqualTo("An order with number 'ORD-LIFECYCLE-DUP' already exists");

        assertThat(orderRepository.findByOrderNumber("ORD-LIFECYCLE-DUP")).isPresent();
    }

    @Test
    void placeOrder_twoConcurrentRequestsWithTheSameNumber_exactlyOneWins() throws Exception {
        // The test above sends the duplicate *after* the first insert committed. Here two requests
        // are released at the same instant, which is the realistic double-click / client retry
        // case. Only the database's unique constraint can referee that: an "if exists" check in
        // Java would let both through when they interleave.
        String body = """
                {
                  "orderNumber": "ORD-LIFECYCLE-RACE",
                  "customerEmail": "alice@example.com",
                  "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
                }
                """;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> placeOrder = () -> {
                startGate.await();
                return client.post().uri("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange()
                        .returnResult(String.class)
                        .getStatus().value();
            };
            Future<Integer> first = executor.submit(placeOrder);
            Future<Integer> second = executor.submit(placeOrder);
            startGate.countDown();

            // Which one wins is up to timing; that exactly one does is not.
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdownNow();
        }

        assertThat(orderRepository.findByOrderNumber("ORD-LIFECYCLE-RACE")).isPresent();
    }
}
