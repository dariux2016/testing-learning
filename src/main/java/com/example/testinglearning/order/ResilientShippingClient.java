package com.example.testinglearning.order;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Wraps {@link ShippingClient} in a Resilience4j Retry and CircuitBreaker, both named
 * {@value #INSTANCE}. Their settings come from the {@code resilience4j.*.instances.shipping.*}
 * properties, which resilience4j-spring-boot4 binds into the two registries.
 *
 * <p>The decoration is written out in code on purpose, instead of using the
 * {@code @Retry}/{@code @CircuitBreaker} annotations. That way:
 * <ul>
 *   <li>the order is visible: Retry is the <em>outer</em> layer, so each retry attempt passes
 *       through the circuit breaker and counts as its own call;</li>
 *   <li>no AOP proxy is involved, so {@code ResilientShippingClientUnitTest} can build real
 *       Retry/CircuitBreaker instances and test this class with no Spring context at all.</li>
 * </ul>
 */
@Component
public class ResilientShippingClient {

    static final String INSTANCE = "shipping";

    private final ShippingClient delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ResilientShippingClient(
            ShippingClient delegate, RetryRegistry retryRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.delegate = delegate;
        this.retry = retryRegistry.retry(INSTANCE);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
    }

    public String createShipment(Order order) {
        Supplier<String> call = () -> delegate.createShipment(order);
        Supplier<String> guardedByCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        Supplier<String> retried = Retry.decorateSupplier(retry, guardedByCircuitBreaker);

        try {
            return retried.get();
        } catch (CallNotPermittedException e) {
            // The circuit is open, so the carrier wasn't even called. The retry config only retries
            // ShippingUnavailableException, so this reaches us without being retried. Translate it
            // here, so callers above this class only ever see our two exceptions.
            throw new ShippingUnavailableException(
                    "Shipping carrier circuit is open; not calling it for order '" + order.getOrderNumber() + "'", e);
        }
    }
}
