package com.example.testinglearning.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Autowired
    private RestTestClient client;

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

        client.post().uri("/api/orders/{id}/ship", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("SHIPPED");

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.SHIPPED);
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
}
