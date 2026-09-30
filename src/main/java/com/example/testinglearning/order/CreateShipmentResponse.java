package com.example.testinglearning.order;

/**
 * The part of the carrier's {@code POST /shipments} response we care about. Any other fields the
 * carrier sends are ignored, so the carrier adding a field doesn't break us.
 */
public record CreateShipmentResponse(String trackingNumber) {
}
