package com.example.testinglearning.order;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Second of the three Kafka test levels (see docs/testing-strategy.md #6): a real broker via
 * Testcontainers, a message produced onto the real input topic, and the consumer's side effect
 * (an order transitioning to PAID in the real database) asserted with Awaitility, since
 * consumption happens on the listener container's own thread, not the test thread.
 *
 * <p>Full {@code @SpringBootTest} rather than a narrower slice: Spring Boot has no dedicated
 * {@code @KafkaTest} slice annotation the way it has {@code @WebMvcTest}/{@code @DataJpaTest}, so
 * per docs/testing-strategy.md #1's table (Testcontainers + full context = Integration tier) this
 * is named {@code *IntegrationTest} even though docs/testing-strategy.md #6 calls this level
 * "component" — AGENTS.md's four-suffix naming rule takes precedence over that section's
 * descriptive label.
 *
 * <p>Deliberately produces with a plain {@code KafkaProducer<String, String>}, not the app's own
 * {@link OrderEventPublisher}/{@code KafkaTemplate}: {@code payment-confirmations} is modelled as
 * coming from an external payment system, so the test should look like that system, not reuse
 * this app's internals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PaymentConfirmationConsumerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Autowired
    private OrderRepository orderRepository;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUpProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaProducer<>(props);
    }

    @AfterEach
    void tearDownProducer() {
        producer.close();
    }

    @Test
    void paymentConfirmed_forExistingOrder_marksTheOrderAsPaid() {
        Order order = orderRepository.saveAndFlush(new Order("ORD-KAFKA-1", "alice@example.com", Instant.now()));

        String payload = """
                { "orderId": %d, "paymentReference": "PAY-REF-1", "confirmedAt": "2026-09-17T10:00:00Z" }
                """.formatted(order.getId());
        producer.send(new ProducerRecord<>(
                PaymentConfirmationListener.PAYMENT_CONFIRMATIONS_TOPIC, order.getId().toString(), payload));
        producer.flush();

        // Consumption is asynchronous (a different thread than this test), so poll instead of
        // asserting immediately - never Thread.sleep.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
        });
    }

    @Test
    void paymentConfirmed_withMalformedJson_isRoutedToTheDeadLetterTopicWithoutCrashingTheListener() {
        try (KafkaConsumer<String, String> dltConsumer = dltConsumer()) {
            producer.send(new ProducerRecord<>(
                    PaymentConfirmationListener.PAYMENT_CONFIRMATIONS_TOPIC, "poison-key", "not valid json at all"));
            producer.flush();

            // A fresh consumer group reads the DLT topic from the beginning, so it can also see
            // dead letters produced by *other* test methods in this class (same shared broker,
            // same listener, same topic) - matching on key is what isolates "our" record.
            ConsumerRecord<String, String> dltRecord =
                    pollForRecordWithKey(dltConsumer, "poison-key", Duration.ofSeconds(10));

            assertThat(dltRecord.value()).isEqualTo("not valid json at all");
        }
    }

    @Test
    void paymentConfirmed_forUnknownOrder_retriesWithBackoffThenRoutesToTheDeadLetterTopic() {
        try (KafkaConsumer<String, String> dltConsumer = dltConsumer()) {
            String payload = """
                    { "orderId": 999999999, "paymentReference": "PAY-REF-UNKNOWN", "confirmedAt": "2026-09-17T10:00:00Z" }
                    """;
            Instant sentAt = Instant.now();
            producer.send(new ProducerRecord<>(
                    PaymentConfirmationListener.PAYMENT_CONFIRMATIONS_TOPIC, "999999999", payload));
            producer.flush();

            ConsumerRecord<String, String> dltRecord =
                    pollForRecordWithKey(dltConsumer, "999999999", Duration.ofSeconds(10));
            Duration elapsed = Duration.between(sentAt, Instant.now());

            // KafkaErrorHandlingConfig retries twice, 300ms apart, before recovering to the DLT -
            // unlike the poison-pill case above, this one can only have reached the DLT after both
            // backoff intervals elapsed. A generous lower bound (half the nominal 600ms) keeps this
            // from being flaky while still distinguishing "retried" from "recovered immediately".
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        }
    }

    private KafkaConsumer<String, String> dltConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(PaymentConfirmationListener.PAYMENT_CONFIRMATIONS_TOPIC + "-dlt"));
        return consumer;
    }

    private static ConsumerRecord<String, String> pollForRecordWithKey(
            KafkaConsumer<String, String> consumer, String expectedKey, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (expectedKey.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No record with key '" + expectedKey + "' received on the DLT within " + timeout);
    }
}
