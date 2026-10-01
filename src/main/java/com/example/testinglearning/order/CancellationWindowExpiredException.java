package com.example.testinglearning.order;

import java.time.Duration;

/**
 * The order is in a cancellable status, but too much time has passed since it was placed (see
 * {@link OrderService#CANCELLATION_WINDOW}).
 */
public class CancellationWindowExpiredException extends RuntimeException {

    public CancellationWindowExpiredException(Long orderId, Duration window) {
        super("Order " + orderId + " can no longer be cancelled: the " + window.toMinutes()
                + "-minute cancellation window has passed");
    }
}
