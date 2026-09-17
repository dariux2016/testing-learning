package com.example.testinglearning.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * JSON body for {@code POST /api/orders}. A separate DTO rather than binding straight onto the
 * {@link Order} entity, so the API contract doesn't change every time the persistence model does.
 * No Bean Validation annotations yet — that's deliberately deferred to the edge-cases step (§9).
 */
public record PlaceOrderRequest(String orderNumber, String customerEmail, List<Item> items) {

    public record Item(String productName, int quantity, BigDecimal unitPrice) {

        OrderItem toOrderItem() {
            return new OrderItem(productName, quantity, unitPrice);
        }
    }

    List<OrderItem> toOrderItems() {
        // null is passed through on purpose: the service owns the "at least one item" rule.
        return items == null ? null : items.stream().map(Item::toOrderItem).toList();
    }
}
