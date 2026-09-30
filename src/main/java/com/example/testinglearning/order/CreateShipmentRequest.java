package com.example.testinglearning.order;

import java.util.List;

/**
 * JSON body {@link ShippingClient} sends to the carrier's {@code POST /shipments}. This is the
 * <em>carrier's</em> contract, not ours, so it's a separate record rather than the {@link Order}
 * entity or {@link OrderResponse}. When the carrier changes its API, only this record and
 * {@link #from(Order)} have to change.
 */
public record CreateShipmentRequest(String reference, String recipientEmail, List<Parcel> parcels) {

    public record Parcel(String description, int quantity) {
    }

    static CreateShipmentRequest from(Order order) {
        return new CreateShipmentRequest(
                order.getOrderNumber(),
                order.getCustomerEmail(),
                order.getItems().stream()
                        .map(item -> new Parcel(item.getProductName(), item.getQuantity()))
                        .toList());
    }
}
