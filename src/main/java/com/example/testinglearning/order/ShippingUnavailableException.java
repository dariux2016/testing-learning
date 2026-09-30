package com.example.testinglearning.order;

/**
 * The carrier couldn't give us an answer: 5xx, 429, a timeout, a dropped connection, an unreadable
 * body, or the circuit breaker being open. The same request might succeed later, so this is the
 * one exception {@link ResilientShippingClient} retries and counts toward the circuit breaker.
 */
public class ShippingUnavailableException extends RuntimeException {

    public ShippingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
