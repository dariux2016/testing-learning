package com.example.testinglearning.order;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests (jqwik) for {@link ShippingClient}'s pure functions (see
 * docs/testing-strategy.md §9).
 *
 * <p>{@link ShippingClientUnitTest} checks hand-picked examples: 400, 422, 429, 500. Here a rule is
 * stated once and jqwik checks it against generated inputs. For a small finite range like
 * {@code 400..499}, jqwik notices it has fewer values than its try budget and simply checks
 * <em>every</em> one ("exhaustive generation"). So "every 4xx except 429 is a rejection" is
 * actually proven for all 100 codes, including odd ones like 418 or 499 that nobody would think to
 * write an example for. When a property fails, jqwik shrinks the input to the smallest
 * counterexample and prints it.
 */
class ShippingClientPropertyUnitTest {

    @Property
    void translate_everyClientErrorExcept429_isARejectionCarryingThatStatus(
            @ForAll @IntRange(min = 400, max = 499) int status) {
        Assume.that(status != 429);

        RuntimeException result = ShippingClient.translate("ORD-1", responseException(status));

        assertThat(result).isInstanceOf(ShipmentRejectedException.class);
        assertThat(((ShipmentRejectedException) result).getCarrierStatus()).isEqualTo(status);
    }

    @Property
    void translate_everyServerError_isUnavailable(@ForAll @IntRange(min = 500, max = 599) int status) {
        assertThat(ShippingClient.translate("ORD-1", responseException(status)))
                .isInstanceOf(ShippingUnavailableException.class);
    }

    @Property
    void translate_alwaysNamesTheOrderAndKeepsTheCause(
            @ForAll @IntRange(min = 400, max = 599) int status,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String orderNumber) {
        RestClientResponseException cause = responseException(status);

        RuntimeException result = ShippingClient.translate(orderNumber, cause);

        assertThat(result).hasMessageContaining("'" + orderNumber + "'").hasCause(cause);
    }

    @Property
    void createShipmentRequest_hasOneParcelPerItemWithTheSameQuantities(
            @ForAll @Size(min = 1, max = 20) List<@IntRange(min = 1, max = 1000) Integer> quantities) {
        Order order = new Order("ORD-1", "alice@example.com", Instant.parse("2026-10-01T10:00:00Z"));
        for (int i = 0; i < quantities.size(); i++) {
            order.addItem(new OrderItem("Product " + i, quantities.get(i), new BigDecimal("1.00")));
        }

        CreateShipmentRequest request = CreateShipmentRequest.from(order);

        assertThat(request.parcels())
                .extracting(CreateShipmentRequest.Parcel::quantity)
                .containsExactlyElementsOf(quantities);
    }

    /** Any status code, including ones Spring's HttpStatus enum doesn't name (e.g. 499). */
    private static RestClientResponseException responseException(int status) {
        return new RestClientResponseException(
                "HTTP " + status, HttpStatusCode.valueOf(status), "status " + status, null, null, null);
    }
}
