package com.example.testinglearning.order;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Producer-side wrapper around {@link KafkaTemplate} for order events (see
 * docs/testing-strategy.md #6, producer-side testing level). Keeping this as its own small class
 * - rather than calling {@code kafkaTemplate.send(...)} straight from {@link OrderService} - is
 * what makes the message shape (topic, key, headers) a single, deliberate place to look at and
 * test, the same reasoning that keeps DTO-building out of the controller in {@link OrderResponse}.
 */
@Component
public class OrderEventPublisher {

    public static final String ORDER_PLACED_TOPIC = "order-placed";
    public static final String EVENT_TYPE_HEADER = "event-type";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderPlaced(Order order) {
        OrderPlacedEvent event = new OrderPlacedEvent(
                order.getId(), order.getOrderNumber(), order.getCustomerEmail(), Instant.now());

        // Keyed by orderNumber (not id) so every event for the same order lands on the same
        // partition, and so a consumer never needs a database round trip just to correlate events.
        Message<OrderPlacedEvent> message = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, ORDER_PLACED_TOPIC)
                .setHeader(KafkaHeaders.KEY, order.getOrderNumber())
                .setHeader(EVENT_TYPE_HEADER, "OrderPlaced")
                .build();

        kafkaTemplate.send(message);
    }
}
