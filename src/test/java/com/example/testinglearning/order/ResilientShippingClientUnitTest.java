package com.example.testinglearning.order;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResilientShippingClient}: retry and circuit-breaker behavior, with the raw
 * {@link ShippingClient} mocked and <em>real</em> Resilience4j Retry/CircuitBreaker instances. No
 * Spring context, no network. The decoration is plain code, not an AOP proxy, which is what makes
 * that possible.
 *
 * <p>The configs are built right here in the test, with small numbers chosen to make each scenario
 * easy to count by hand. That also means these tests <em>don't</em> prove that
 * {@code application.properties} is bound correctly. {@link OrderShippingIntegrationTest} covers
 * that against the real, property-configured registries.
 */
@ExtendWith(MockitoExtension.class)
class ResilientShippingClientUnitTest {

    @Mock
    private ShippingClient shippingClient;

    private CircuitBreakerRegistry circuitBreakerRegistry;
    private ResilientShippingClient resilientClient;

    private final Order order = new Order("ORD-1", "alice@example.com", Instant.parse("2026-09-30T10:00:00Z"));

    @BeforeEach
    void setUp() {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(ShippingUnavailableException.class)
                .build());

        // Opens as soon as 4 calls have been recorded and at least half of them failed.
        circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(ShippingUnavailableException.class)
                .ignoreExceptions(ShipmentRejectedException.class)
                .build());

        resilientClient = new ResilientShippingClient(shippingClient, retryRegistry, circuitBreakerRegistry);
    }

    // --- retry ----------------------------------------------------------

    @Test
    void createShipment_whenCarrierAnswersFirstTime_callsItOnce() {
        when(shippingClient.createShipment(order)).thenReturn("TRACK-1");

        assertThat(resilientClient.createShipment(order)).isEqualTo("TRACK-1");

        verify(shippingClient, times(1)).createShipment(order);
    }

    @Test
    void createShipment_whenCarrierIsUnavailableTwiceThenRecovers_retriesAndSucceeds() {
        when(shippingClient.createShipment(order))
                .thenThrow(unavailable())
                .thenThrow(unavailable())
                .thenReturn("TRACK-1");

        assertThat(resilientClient.createShipment(order)).isEqualTo("TRACK-1");

        verify(shippingClient, times(3)).createShipment(order);
    }

    @Test
    void createShipment_whenCarrierStaysUnavailable_givesUpAfterMaxAttempts() {
        when(shippingClient.createShipment(order)).thenThrow(unavailable());

        assertThatThrownBy(() -> resilientClient.createShipment(order))
                .isInstanceOf(ShippingUnavailableException.class);

        verify(shippingClient, times(3)).createShipment(order);
    }

    @Test
    void createShipment_whenCarrierRejects_doesNotRetry() {
        // Asking again won't change a "no", so exactly one call.
        when(shippingClient.createShipment(order)).thenThrow(new ShipmentRejectedException("ORD-1", 422, null));

        assertThatThrownBy(() -> resilientClient.createShipment(order))
                .isInstanceOf(ShipmentRejectedException.class);

        verify(shippingClient, times(1)).createShipment(order);
    }

    // --- circuit breaker --------------------------------------------------

    @Test
    void createShipment_afterEnoughFailures_opensTheCircuitAndStopsCallingTheCarrier() {
        when(shippingClient.createShipment(order)).thenThrow(unavailable());

        // Call 1: 3 attempts, all failing, so 3 failures recorded (still below the 4-call minimum).
        // Call 2: the 1st attempt is the 4th failure, and the circuit opens. The 2nd attempt is
        // refused with CallNotPermittedException, which isn't retryable, so the retry stops there.
        assertThatThrownBy(() -> resilientClient.createShipment(order)).isInstanceOf(ShippingUnavailableException.class);
        assertThatThrownBy(() -> resilientClient.createShipment(order)).isInstanceOf(ShippingUnavailableException.class);
        verify(shippingClient, times(4)).createShipment(order);
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clearInvocations(shippingClient);

        // Call 3: rejected by the open circuit before reaching the carrier at all.
        assertThatThrownBy(() -> resilientClient.createShipment(order))
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessageContaining("circuit is open")
                .hasCauseInstanceOf(CallNotPermittedException.class);
        verifyNoInteractions(shippingClient);
    }

    @Test
    void createShipment_rejectionsNeverOpenTheCircuit() {
        when(shippingClient.createShipment(order)).thenThrow(new ShipmentRejectedException("ORD-1", 422, null));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> resilientClient.createShipment(order))
                    .isInstanceOf(ShipmentRejectedException.class);
        }

        // A carrier that answers "no" is healthy; ignoreExceptions keeps these out of the stats.
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(shippingClient, times(10)).createShipment(order);
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(ResilientShippingClient.INSTANCE);
    }

    private static ShippingUnavailableException unavailable() {
        return new ShippingUnavailableException("carrier down", null);
    }
}
