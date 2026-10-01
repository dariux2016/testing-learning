package com.example.testinglearning.order;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.test.context.support.WithMockUser;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Third of the three Kafka test levels (see docs/testing-strategy.md #6): the producer wrapper,
 * {@link OrderEventPublisher}, tested against the same kind of real Testcontainers broker as
 * {@link PaymentConfirmationConsumerIntegrationTest} — but consuming from the topic in the test
 * this time, to assert the message shape, key, and headers actually put on the wire.
 *
 * <p>Uses a plain {@code KafkaConsumer<String, String>} rather than the app's own
 * deserialization config, so the assertions below are checking the literal bytes/headers any
 * external consumer would see, not just that Spring's own (de)serializer round-trips correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
// placeOrder() is protected by method security (@PreAuthorize, see OrderService). This test calls it
// directly, not over HTTP, so no token is involved: @WithMockUser puts a STAFF user into the
// SecurityContext for each test method instead.
@WithMockUser(roles = "STAFF")
class OrderEventPublisherIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Autowired
    private OrderService orderService;

    private final ObjectMapper objectMapper = new JsonMapper();

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-order-placed-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(OrderEventPublisher.ORDER_PLACED_TOPIC));
    }

    @AfterEach
    void tearDownConsumer() {
        consumer.close();
    }

    @Test
    void placeOrder_publishesAnOrderPlacedEventWithTheOrderNumberAsKeyAndAnEventTypeHeader() {
        Order placed = orderService.placeOrder("ORD-KAFKA-EVT-1", "alice@example.com",
                List.of(new OrderItem("Widget", 2, new BigDecimal("9.99"))));

        ConsumerRecord<String, String> record = pollForOneRecord(Duration.ofSeconds(10));

        assertThat(record.key()).isEqualTo("ORD-KAFKA-EVT-1");

        Header eventTypeHeader = record.headers().lastHeader(OrderEventPublisher.EVENT_TYPE_HEADER);
        assertThat(eventTypeHeader).isNotNull();
        assertThat(new String(eventTypeHeader.value(), StandardCharsets.UTF_8)).isEqualTo("OrderPlaced");

        JsonNode body = objectMapper.readTree(record.value());
        assertThat(body.get("orderId").asLong()).isEqualTo(placed.getId());
        assertThat(body.get("orderNumber").asString()).isEqualTo("ORD-KAFKA-EVT-1");
        assertThat(body.get("customerEmail").asString()).isEqualTo("alice@example.com");
        // placedAt is the order's own createdAt, not a second reading of the clock, so it can be
        // compared exactly rather than with "roughly now".
        assertThat(Instant.parse(body.get("placedAt").asString())).isEqualTo(placed.getCreatedAt());
    }

    private ConsumerRecord<String, String> pollForOneRecord(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("No record received on " + OrderEventPublisher.ORDER_PLACED_TOPIC
                + " within " + timeout);
    }
}
