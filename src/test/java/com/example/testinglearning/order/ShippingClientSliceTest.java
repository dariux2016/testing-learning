package com.example.testinglearning.order;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice tests for {@link ShippingClient} against a real HTTP server, WireMock, standing in for the
 * carrier (see docs/testing-strategy.md §7).
 *
 * <p>{@code @RestClientTest} starts only what an HTTP client needs: Boot's {@code RestClient.Builder}
 * with Jackson, plus the class under test. By default it also swaps in Spring's
 * {@code MockRestServiceServer}, which fakes responses <em>without opening a socket</em>. That's
 * turned off here ({@code enabled = false}) because half of what this class must handle only exists
 * on a real connection: read timeouts, slow responses, dropped connections. WireMock produces all
 * of those for real.
 *
 * <p>Retry and circuit breaking are not in this slice. {@link ResilientShippingClient} isn't
 * loaded, so every test below sees exactly one HTTP call and its raw translation.
 * {@code test/resources/application.properties} sets the read timeout to 500ms.
 */
@RestClientTest(ShippingClient.class)
@AutoConfigureMockRestServiceServer(enabled = false)
class ShippingClientSliceTest {

    // Static + dynamicPort: one server for the class, on a free port, started before the Spring
    // context resolves the base URL below.
    @RegisterExtension
    static final WireMockExtension carrier = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void carrierUrl(DynamicPropertyRegistry registry) {
        registry.add("shipping.client.base-url", carrier::baseUrl);
    }

    @Autowired
    private ShippingClient shippingClient;

    // --- success ----------------------------------------------------------

    @Test
    void createShipment_on201_returnsTrackingNumber() {
        carrier.stubFor(post("/shipments").willReturn(
                aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        // An extra field the carrier added: it must be ignored, not break us.
                        .withBody("""
                                { "trackingNumber": "TRACK-123", "estimatedDelivery": "2026-10-03" }
                                """)));

        assertThat(shippingClient.createShipment(paidOrder())).isEqualTo("TRACK-123");
    }

    @Test
    void createShipment_sendsTheExpectedRequest() {
        carrier.stubFor(post("/shipments").willReturn(okJson("""
                { "trackingNumber": "TRACK-123" }
                """)));

        shippingClient.createShipment(paidOrder());

        // What goes over the wire is the contract with the carrier, so it's worth pinning exactly.
        carrier.verify(1, postRequestedFor(urlEqualTo("/shipments"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader(ShippingClient.IDEMPOTENCY_KEY_HEADER, equalTo("ORD-1"))
                .withRequestBody(equalToJson("""
                        {
                          "reference": "ORD-1",
                          "recipientEmail": "alice@example.com",
                          "parcels": [ { "description": "Widget", "quantity": 2 } ]
                        }
                        """)));
    }

    @Test
    void createShipment_whenSlowButWithinReadTimeout_stillSucceeds() {
        carrier.stubFor(post("/shipments").willReturn(okJson("""
                { "trackingNumber": "TRACK-SLOW" }
                """).withFixedDelay(200)));

        assertThat(shippingClient.createShipment(paidOrder())).isEqualTo("TRACK-SLOW");
    }

    // --- carrier says no (4xx) ------------------------------------------------

    @Test
    void createShipment_on400_throwsRejected() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShipmentRejectedException.class)
                .hasMessage("Shipping carrier rejected the shipment for order 'ORD-1' (HTTP 400)");
    }

    @Test
    void createShipment_on422WithProblemBody_throwsRejected() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("""
                        { "title": "Undeliverable address" }
                        """)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOfSatisfying(ShipmentRejectedException.class,
                        e -> assertThat(e.getCarrierStatus()).isEqualTo(422));
    }

    // --- carrier can't answer (5xx, 429, I/O, garbage) -------------------------

    @Test
    void createShipment_on429_throwsUnavailable() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessage("Shipping carrier answered HTTP 429 for order 'ORD-1'");
    }

    @Test
    void createShipment_on500_throwsUnavailable() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessage("Shipping carrier answered HTTP 500 for order 'ORD-1'");
    }

    @Test
    void createShipment_on503_throwsUnavailable() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class);
    }

    @Test
    void createShipment_whenSlowerThanReadTimeout_throwsUnavailable() {
        // 2s delay against a 500ms read timeout. MockRestServiceServer can't simulate this.
        carrier.stubFor(post("/shipments").willReturn(okJson("""
                { "trackingNumber": "TOO-LATE" }
                """).withFixedDelay(2_000)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void createShipment_whenConnectionIsReset_throwsUnavailable() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class);
    }

    @Test
    void createShipment_on200WithMalformedJson_throwsUnavailable() {
        carrier.stubFor(post("/shipments").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{ not json")));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class);
    }

    @Test
    void createShipment_on200WithoutTrackingNumber_throwsUnavailable() {
        // Valid JSON, but missing the one field we need. That can't count as a success.
        carrier.stubFor(post("/shipments").willReturn(okJson("{}")));

        assertThatThrownBy(() -> shippingClient.createShipment(paidOrder()))
                .isInstanceOf(ShippingUnavailableException.class)
                .hasMessage("Shipping carrier returned no tracking number for order 'ORD-1'");
    }

    private static Order paidOrder() {
        Order order = new Order("ORD-1", "alice@example.com", Instant.parse("2026-09-30T10:00:00Z"));
        order.addItem(new OrderItem("Widget", 2, new BigDecimal("9.99")));
        order.setStatus(OrderStatus.PAID);
        return order;
    }
}
