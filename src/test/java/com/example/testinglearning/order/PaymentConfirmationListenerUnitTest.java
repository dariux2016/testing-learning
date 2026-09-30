package com.example.testinglearning.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PaymentConfirmationListener} — the first of the three Kafka test levels
 * (see docs/testing-strategy.md #6). No Spring context and no Kafka broker at all: the
 * {@code @KafkaListener}-annotated method is called directly like any other method, with {@link
 * OrderService} mocked, so this is purely testing "does the listener call the service with the
 * right argument, and does it let the service's exceptions propagate" — not Kafka wiring, which
 * belongs to {@link PaymentConfirmationConsumerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmationListenerUnitTest {

    @Mock
    private OrderService orderService;

    private PaymentConfirmationListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentConfirmationListener(orderService);
    }

    @Test
    void onPaymentConfirmed_marksTheReferencedOrderAsPaid() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(42L, "PAY-REF-1", Instant.now());

        listener.onPaymentConfirmed(event);

        verify(orderService).markAsPaid(42L);
    }

    @Test
    void onPaymentConfirmed_whenOrderServiceThrows_propagatesTheException() {
        // Propagating (rather than swallowing) is what lets the container's error handler
        // (KafkaErrorHandlingConfig) retry and eventually route to the dead-letter topic.
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(99L, "PAY-REF-2", Instant.now());
        when(orderService.markAsPaid(99L)).thenThrow(new OrderNotFoundException(99L));

        assertThatThrownBy(() -> listener.onPaymentConfirmed(event))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("99");
    }
}
