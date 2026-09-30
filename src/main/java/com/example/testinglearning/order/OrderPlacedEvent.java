package com.example.testinglearning.order;

import java.time.Instant;

/**
 * Payload published by {@link OrderEventPublisher} to the {@code order-placed} topic whenever
 * {@link OrderService#placeOrder} succeeds. A separate type from {@link Order}/{@link
 * com.example.testinglearning.order.OrderResponse} for the same reason the HTTP DTOs are separate
 * from the entity: the wire contract with downstream Kafka consumers shouldn't change just
 * because the persistence model does.
 */
public record OrderPlacedEvent(Long orderId, String orderNumber, String customerEmail, Instant placedAt) {
}
