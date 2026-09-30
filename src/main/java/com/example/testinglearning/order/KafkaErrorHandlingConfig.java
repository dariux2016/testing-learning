package com.example.testinglearning.order;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Failure-mode handling for Kafka listeners (see docs/testing-strategy.md #6). Spring Boot
 * auto-detects a single {@code CommonErrorHandler} bean and wires it into the default listener
 * container factory, so this applies to every {@code @KafkaListener} in the app - currently just
 * {@link PaymentConfirmationListener}.
 *
 * <p>Two failure modes, both ending up on the {@code <topic>-dlt} topic (spring-kafka 4's default
 * dead-letter topic naming - not the older {@code <topic>.DLT} convention) via {@link
 * DeadLetterPublishingRecoverer}, but reached differently:
 * <ul>
 *   <li><b>Poison pill</b> (a message that fails to deserialize): {@code ErrorHandlingDeserializer}
 *       (configured in application.properties) turns that into a {@code DeserializationException}
 *       delivered to the container. Spring Kafka classifies deserialization exceptions as fatal by
 *       default, so the recoverer runs immediately - no retries, since retrying would just fail to
 *       deserialize the same bytes again. The recoverer sees the original <b>raw bytes</b> (
 *       {@code byte[]}) for this case - there was never a {@link PaymentConfirmedEvent} to begin
 *       with.</li>
 *   <li><b>A business exception</b> from the listener itself (e.g. {@link OrderNotFoundException}
 *       because the payment confirmation referenced an order id that doesn't exist): retried per
 *       the {@link FixedBackOff} below, then recovered to the DLT once attempts are exhausted. The
 *       recoverer sees the already-deserialized <b>{@link PaymentConfirmedEvent}</b> for this
 *       case.</li>
 * </ul>
 *
 * <p>Those two cases need different producer serializers for the value, which is why the
 * recoverer below is built from a {@code Map<Class<?>, KafkaOperations<?, ?>>}: raw {@code byte[]}
 * values go through a plain {@code ByteArraySerializer} (so a poison pill's bytes are preserved
 * verbatim on the DLT, not re-encoded as a base64 JSON string by the app's normal JSON
 * serializer), while {@link PaymentConfirmedEvent} values go through the app's own {@code
 * KafkaTemplate} (JacksonJsonSerializer, same as every other producer in the app).
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            DefaultKafkaProducerFactory<Object, Object> producerFactory, KafkaTemplate<Object, Object> kafkaTemplate) {
        ProducerFactory<Object, Object> byteArrayProducerFactory = producerFactory.copyWithConfigurationOverride(
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(Map.of(
                byte[].class, new KafkaTemplate<>(byteArrayProducerFactory),
                PaymentConfirmedEvent.class, kafkaTemplate));

        // 2 retries, 300ms apart, before giving up and publishing to the DLT.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(300L, 2L));
    }
}
