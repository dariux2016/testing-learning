package com.example.testinglearning.order;

import java.time.Instant;

/**
 * Payload consumed by {@link PaymentConfirmationListener} from the {@code payment-confirmations}
 * topic. Modelled as arriving from an external payment system, not something this application
 * produces itself - hence no {@code event-type} header contract to lean on and no Spring Kafka
 * {@code __TypeId__} type header to rely on for deserialization (see application.properties).
 */
public record PaymentConfirmedEvent(Long orderId, String paymentReference, Instant confirmedAt) {
}
