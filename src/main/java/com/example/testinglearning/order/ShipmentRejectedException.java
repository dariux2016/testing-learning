package com.example.testinglearning.order;

/**
 * The carrier understood the request and said no (a 4xx other than 429), e.g. an address it can't
 * deliver to. Retrying the same request won't change the answer, so this is deliberately
 * <em>not</em> retried and doesn't count as a failure for the circuit breaker.
 */
public class ShipmentRejectedException extends RuntimeException {

    private final int carrierStatus;

    public ShipmentRejectedException(String orderNumber, int carrierStatus, Throwable cause) {
        super("Shipping carrier rejected the shipment for order '" + orderNumber + "' (HTTP " + carrierStatus + ")",
                cause);
        this.carrierStatus = carrierStatus;
    }

    public int getCarrierStatus() {
        return carrierStatus;
    }
}
