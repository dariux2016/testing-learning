package com.example.testinglearning.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer side of the Kafka slice (see docs/testing-strategy.md #6). Listens for payment
 * confirmations from an external payment system and drives the existing {@code CREATED -> PAID}
 * transition already covered by {@link OrderServiceUnitTest}. Deliberately thin: all the
 * transition logic (and its exceptions) stays in {@link OrderService}, so this class has nothing
 * to unit-test beyond "did it call the service with the right argument, and does it let
 * exceptions propagate so the container's error handler (see {@link KafkaErrorHandlingConfig})
 * can retry/DLT them".
 */
@Component
public class PaymentConfirmationListener {

    public static final String PAYMENT_CONFIRMATIONS_TOPIC = "payment-confirmations";

    private final OrderService orderService;

    public PaymentConfirmationListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = PAYMENT_CONFIRMATIONS_TOPIC, groupId = "order-service")
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        orderService.markAsPaid(event.orderId());
    }
}
