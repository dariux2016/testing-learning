package com.example.testinglearning.order;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security from end to end, with nothing mocked (see docs/testing-strategy.md §8): a real Keycloak
 * issues real signed tokens, and the app, on a real port, validates them against Keycloak's
 * published keys (the {@code issuer-uri} is pointed at the container below).
 *
 * <p>Every other security test takes a shortcut: {@code jwt()}/{@code @WithMockUser} skip token
 * decoding, and the integration tests' mocked {@code JwtDecoder} skips signature checks. This one
 * proves the parts those skip: signature and issuer validation, and that a <em>real</em> Keycloak
 * token has the claim shapes ({@code email}, {@code realm_access.roles}) our converter expects.
 * It's the most expensive test in the suite, so it's kept to two scenarios. The detailed
 * 401/403/200 matrix lives in the cheaper slices.
 *
 * <p>The realm (users {@code alice} = CUSTOMER and {@code sam} = STAFF) is imported from
 * {@code src/test/resources/keycloak/orders-realm.json}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
class OrderSecurityKeycloakIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // placeOrder() publishes an OrderPlacedEvent, so a broker is needed for the same reason as in
    // OrderLifecycleIntegrationTest.
    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.7")
            .withRealmImportFile("/keycloak/orders-realm.json");

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/orders");
    }

    @Autowired
    private RestTestClient client;

    @Test
    void customerWithRealToken_canPlaceAndReadOwnOrder_butCannotShipIt() {
        String aliceToken = accessToken("alice", "alice-password");

        OrderResponse created = client.post().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-KC-1",
                          "customerEmail": "alice@example.com",
                          "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        // The ownership rule (@PostAuthorize) compared the real token's email claim to the order.
        client.get().uri("/api/orders/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.customerEmail").isEqualTo("alice@example.com");

        // Keycloak's realm_access.roles really became ROLE_CUSTOMER, and that isn't STAFF.
        client.post().uri("/api/orders/{id}/ship", created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void staffWithRealToken_canPayAnyOrder_andAForgedTokenIsRejected() {
        String samToken = accessToken("sam", "sam-password");

        OrderResponse created = client.post().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + samToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-KC-2",
                          "customerEmail": "bob@example.com",
                          "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        client.post().uri("/api/orders/{id}/pay", created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + samToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PAID");

        // Flip one character of the signature: the header and claims are untouched and still say
        // STAFF, but the signature no longer matches Keycloak's key. Only a real decoder notices.
        String forged = samToken.substring(0, samToken.length() - 2)
                + (samToken.charAt(samToken.length() - 2) == 'A' ? 'B' : 'A')
                + samToken.charAt(samToken.length() - 1);
        assertThat(forged).isNotEqualTo(samToken);

        client.post().uri("/api/orders/{id}/cancel", created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Logs in with the password grant, which the test-only client allows. This is plain HTTP
     * against Keycloak's token endpoint, exactly as any OAuth client would do it.
     */
    private static String accessToken(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "orders-api-test");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");

        Map<String, Object> response = RestClient.create()
                .post()
                .uri(keycloak.getAuthServerUrl() + "/realms/orders/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        return (String) response.get("access_token");
    }
}
