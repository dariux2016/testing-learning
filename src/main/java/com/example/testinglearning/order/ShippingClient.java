package com.example.testinglearning.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Makes one raw HTTP call to the external shipping carrier (see docs/testing-strategy.md §7).
 *
 * <p>This class owns three things and nothing more: the request (URL, headers, JSON body built by
 * {@link CreateShipmentRequest#from(Order)}), the response mapping, and turning every way the call
 * can fail into one of our two exceptions ({@link #translate}). Retry and circuit breaking are
 * deliberately <em>not</em> here. They live in {@link ResilientShippingClient}, so each class can
 * be tested for exactly one concern.
 */
@Component
public class ShippingClient {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public ShippingClient(
            RestClient.Builder restClientBuilder,
            @Value("${shipping.client.base-url}") String baseUrl,
            @Value("${shipping.client.connect-timeout}") Duration connectTimeout,
            @Value("${shipping.client.read-timeout}") Duration readTimeout) {
        // Explicit timeouts: without them a hung carrier holds our request thread forever, and the
        // "slow carrier" WireMock tests would never finish. HTTP/1.1 is pinned to stop the JDK
        // client from attempting an h2c upgrade against a plain-HTTP server.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        // Starting from Boot's RestClient.Builder (rather than RestClient.create()) keeps Boot's
        // configured Jackson 3 message converters and observation support.
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Books a shipment for {@code order} and returns the carrier's tracking number.
     *
     * @throws ShipmentRejectedException    the carrier said no (4xx other than 429)
     * @throws ShippingUnavailableException the carrier couldn't answer (5xx, 429, I/O, bad body)
     */
    public String createShipment(Order order) {
        CreateShipmentResponse response;
        try {
            response = restClient.post()
                    .uri("/shipments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    // POST isn't idempotent by itself. This key lets the carrier recognize a retried
                    // request (ResilientShippingClient retries) and avoid booking the parcel twice.
                    .header(IDEMPOTENCY_KEY_HEADER, order.getOrderNumber())
                    .body(CreateShipmentRequest.from(order))
                    .retrieve()
                    .body(CreateShipmentResponse.class);
        } catch (RestClientException e) {
            throw translate(order.getOrderNumber(), e);
        }

        if (response == null || response.trackingNumber() == null || response.trackingNumber().isBlank()) {
            throw new ShippingUnavailableException(
                    "Shipping carrier returned no tracking number for order '" + order.getOrderNumber() + "'", null);
        }
        return response.trackingNumber();
    }

    /**
     * Maps a Spring {@link RestClientException} onto one of our two exceptions. It's a static method
     * with no I/O, so {@code ShippingClientUnitTest} can cover every branch without a server.
     */
    static RuntimeException translate(String orderNumber, RestClientException e) {
        if (e instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            // 429 is a 4xx, but it means "not now" rather than "no", so it's treated as retryable.
            if (responseException.getStatusCode().is4xxClientError()
                    && status != HttpStatus.TOO_MANY_REQUESTS.value()) {
                return new ShipmentRejectedException(orderNumber, status, e);
            }
            return new ShippingUnavailableException(
                    "Shipping carrier answered HTTP " + status + " for order '" + orderNumber + "'", e);
        }
        // ResourceAccessException (timeout, connection refused/reset) and body-extraction failures
        // (a response we can't parse) both land here.
        return new ShippingUnavailableException(
                "Shipping carrier unreachable for order '" + orderNumber + "': " + e.getMessage(), e);
    }
}
