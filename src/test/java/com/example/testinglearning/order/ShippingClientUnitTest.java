package com.example.testinglearning.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure logic around {@link ShippingClient} (see docs/testing-strategy.md §7):
 * mapping an {@link Order} onto the carrier's request body, and turning Spring's
 * {@link RestClientException}s into our two exceptions. No Spring context, no network, no mocks.
 *
 * <p>What's deliberately <em>not</em> here: mocking {@code RestClient}'s fluent chain
 * ({@code post().uri().body().retrieve()...}) to "unit test" {@code createShipment}. Such a test
 * mostly checks that the mocks were chained in the right order. It breaks on harmless refactors and
 * can't say anything about real HTTP (status codes, timeouts, JSON). That part is covered by
 * {@link ShippingClientSliceTest} against a real WireMock server.
 */
class ShippingClientUnitTest {

    // --- request mapping ------------------------------------------------

    @Test
    void createShipmentRequest_mapsOrderNumberEmailAndOneParcelPerItem() {
        Order order = new Order("ORD-1", "alice@example.com", Instant.parse("2026-09-30T10:00:00Z"));
        order.addItem(new OrderItem("Widget", 2, new BigDecimal("9.99")));
        order.addItem(new OrderItem("Gadget", 1, new BigDecimal("24.50")));

        CreateShipmentRequest request = CreateShipmentRequest.from(order);

        assertThat(request.reference()).isEqualTo("ORD-1");
        assertThat(request.recipientEmail()).isEqualTo("alice@example.com");
        // Prices are ours, not the carrier's business: they must not leak into the request.
        assertThat(request.parcels()).containsExactly(
                new CreateShipmentRequest.Parcel("Widget", 2),
                new CreateShipmentRequest.Parcel("Gadget", 1));
    }

    // --- error translation ----------------------------------------------

    @Test
    void translate_400_isARejection() {
        RuntimeException result = ShippingClient.translate("ORD-1", new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThat(result)
                .isInstanceOf(ShipmentRejectedException.class)
                .hasMessage("Shipping carrier rejected the shipment for order 'ORD-1' (HTTP 400)");
        assertThat(((ShipmentRejectedException) result).getCarrierStatus()).isEqualTo(400);
    }

    @Test
    void translate_422_isARejection() {
        RuntimeException result = ShippingClient.translate(
                "ORD-1", new HttpClientErrorException(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThat(result).isInstanceOf(ShipmentRejectedException.class);
        assertThat(((ShipmentRejectedException) result).getCarrierStatus()).isEqualTo(422);
    }

    @Test
    void translate_429_isUnavailableNotARejection() {
        // The one 4xx that means "try again later" rather than "no".
        RuntimeException result = ShippingClient.translate(
                "ORD-1", new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(result)
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessage("Shipping carrier answered HTTP 429 for order 'ORD-1'");
    }

    @Test
    void translate_500_isUnavailable() {
        RuntimeException result = ShippingClient.translate(
                "ORD-1", new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(result)
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessage("Shipping carrier answered HTTP 500 for order 'ORD-1'");
    }

    @Test
    void translate_ioFailure_isUnavailableAndKeepsTheCause() {
        ResourceAccessException timeout = new ResourceAccessException(
                "I/O error on POST request", new HttpTimeoutException("request timed out"));

        RuntimeException result = ShippingClient.translate("ORD-1", timeout);

        assertThat(result)
                .isInstanceOf(ShippingUnavailableException.class)
                .hasCause(timeout);
    }

    @Test
    void translate_unreadableResponseBody_isUnavailable() {
        // What RestClient throws when the carrier sends a 2xx body it can't deserialize.
        RestClientException extractionFailure = new RestClientException(
                "Error while extracting response", new IOException("Unexpected character"));

        RuntimeException result = ShippingClient.translate("ORD-1", extractionFailure);

        assertThat(result).isInstanceOf(ShippingUnavailableException.class);
    }
}
