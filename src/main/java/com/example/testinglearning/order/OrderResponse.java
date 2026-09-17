package com.example.testinglearning.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * JSON representation of an {@link Order} returned by {@link OrderController}. Never serialize the
 * entity itself: the lazy {@code OrderItem.order} back-reference would recurse, and every
 * persistence change would silently become an API change.
 */
public record OrderResponse(
        Long id,
        String orderNumber,
        String customerEmail,
        OrderStatus status,
        Instant createdAt,
        List<Item> items) {

    public record Item(String productName, int quantity, BigDecimal unitPrice) {
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomerEmail(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getItems().stream()
                        .map(item -> new Item(item.getProductName(), item.getQuantity(), item.getUnitPrice()))
                        .toList());
    }
}
