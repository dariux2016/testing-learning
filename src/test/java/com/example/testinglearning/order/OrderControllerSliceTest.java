package com.example.testinglearning.order;

import com.example.testinglearning.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Slice tests for {@link OrderController} (see docs/testing-strategy.md §4).
 *
 * <p>{@code @WebMvcTest} starts only the web layer: this controller, Jackson, the
 * {@code @RestControllerAdvice} ({@link OrderExceptionHandler}) and MockMvc — no JPA, no database,
 * no real {@link OrderService}. The service is replaced by a Mockito mock via {@code @MockitoBean},
 * so these tests prove exactly what the controller owns: URL/verb mapping, request binding and type
 * conversion, the JSON shape in and out, status codes, and the error contract. The service's own
 * rules are already covered by {@link OrderServiceUnitTest}.
 *
 * <p>{@link RestTestClient} is bound to MockMvc here (no real HTTP server); the same client API is
 * reused against a live server in the full integration tests of §5.
 *
 * <p>Security (§8) is real here but kept out of the way: the actual {@link SecurityConfig} filter
 * chain is imported, and every request runs as a STAFF user via {@code @WithMockUser}, so no request
 * is ever turned away. Which roles get 401/403 is {@code OrderControllerSecuritySliceTest}'s job.
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "STAFF")
@AutoConfigureRestTestClient
class OrderControllerSliceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-17T10:15:30Z");

    @Autowired
    private RestTestClient client;

    @MockitoBean
    private OrderService orderService;

    // --- POST /api/orders -----------------------------------------------

    @Test
    void placeOrder_withValidBody_returns201WithLocationAndOrderJson() {
        Order saved = order(42L, "ORD-1", OrderStatus.CREATED);
        saved.addItem(new OrderItem("Widget", 2, new BigDecimal("9.99")));
        when(orderService.placeOrder(eq("ORD-1"), eq("alice@example.com"), anyList())).thenReturn(saved);

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-1",
                          "customerEmail": "alice@example.com",
                          "items": [ { "productName": "Widget", "quantity": 2, "unitPrice": 9.99 } ]
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("http://localhost/api/orders/42")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                // STRICT: the response must have exactly these fields — an accidentally leaked or
                // renamed field is an API break, and this is the test that should catch it.
                .expectBody().json("""
                        {
                          "id": 42,
                          "orderNumber": "ORD-1",
                          "customerEmail": "alice@example.com",
                          "status": "CREATED",
                          "createdAt": "2026-09-17T10:15:30Z",
                          "trackingNumber": null,
                          "items": [ { "productName": "Widget", "quantity": 2, "unitPrice": 9.99 } ]
                        }
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void placeOrder_mapsRequestItemsOntoServiceArguments() {
        when(orderService.placeOrder(anyString(), anyString(), anyList()))
                .thenReturn(order(1L, "ORD-1", OrderStatus.CREATED));

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-1",
                          "customerEmail": "alice@example.com",
                          "items": [
                            { "productName": "Widget", "quantity": 2, "unitPrice": 9.99 },
                            { "productName": "Gadget", "quantity": 1, "unitPrice": 100.50 }
                          ]
                        }
                        """)
                .exchange()
                .expectStatus().isCreated();

        // The DTO -> domain mapping is the controller's own logic, so here the arguments passed to
        // the collaborator *are* the contract worth verifying.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).placeOrder(eq("ORD-1"), eq("alice@example.com"), itemsCaptor.capture());

        assertThat(itemsCaptor.getValue())
                .extracting(OrderItem::getProductName, OrderItem::getQuantity)
                .containsExactly(tuple("Widget", 2), tuple("Gadget", 1));
        assertThat(itemsCaptor.getValue().get(1).getUnitPrice()).isEqualByComparingTo("100.50");
    }

    @Test
    void placeOrder_whenServiceRejectsMissingItems_returns400ProblemDetail() {
        when(orderService.placeOrder(eq("ORD-2"), eq("alice@example.com"), any()))
                .thenThrow(new IllegalArgumentException("An order must have at least one item"));

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        { "orderNumber": "ORD-2", "customerEmail": "alice@example.com" }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                // No "type" member: Spring omits it when it's the default "about:blank", which
                // RFC 9457 defines as equivalent. STRICT mode is what surfaced that detail.
                .expectBody().json("""
                        {
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "An order must have at least one item",
                          "instance": "/api/orders"
                        }
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void placeOrder_whenOrderNumberAlreadyExists_returns409ProblemDetail() {
        when(orderService.placeOrder(anyString(), anyString(), anyList()))
                .thenThrow(new DuplicateOrderNumberException("ORD-1", new DataIntegrityViolationException("dup")));

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-1",
                          "customerEmail": "alice@example.com",
                          "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Duplicate order number")
                .jsonPath("$.detail").isEqualTo("An order with number 'ORD-1' already exists");
    }

    @Test
    void placeOrder_withMalformedJson_returns400AndNeverCallsService() {
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{ \"orderNumber\": \"ORD-1\", ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(400);

        verifyNoInteractions(orderService);
    }

    @Test
    void placeOrder_withWrongJsonType_returns400AndNeverCallsService() {
        // "quantity" must be an int; Jackson fails to bind before the controller method runs.
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "orderNumber": "ORD-1",
                          "customerEmail": "alice@example.com",
                          "items": [ { "productName": "Widget", "quantity": "two", "unitPrice": 9.99 } ]
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(orderService);
    }

    @Test
    void placeOrder_withNonJsonContentType_returns415() {
        client.post().uri("/api/orders")
                .contentType(MediaType.TEXT_PLAIN)
                .body("ORD-1")
                .exchange()
                .expectStatus().isEqualTo(415);

        verifyNoInteractions(orderService);
    }

    // --- GET /api/orders/{id} -------------------------------------------

    @Test
    void getOrder_whenFound_returns200WithOrderJson() {
        when(orderService.getOrder(7L)).thenReturn(order(7L, "ORD-7", OrderStatus.PAID));

        client.get().uri("/api/orders/7")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(7)
                .jsonPath("$.orderNumber").isEqualTo("ORD-7")
                .jsonPath("$.status").isEqualTo("PAID")
                .jsonPath("$.items").isEmpty();
    }

    @Test
    void getOrder_whenNotFound_returns404ProblemDetail() {
        when(orderService.getOrder(99L)).thenThrow(new OrderNotFoundException(99L));

        client.get().uri("/api/orders/99")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().json("""
                        {
                          "title": "Order not found",
                          "status": 404,
                          "detail": "No order found with id 99",
                          "instance": "/api/orders/99"
                        }
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void getOrder_withNonNumericId_returns400AndNeverCallsService() {
        // {id} is bound to Long — "abc" fails type conversion before the controller method runs.
        client.get().uri("/api/orders/abc")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(400);

        verifyNoInteractions(orderService);
    }

    @Test
    void getOrder_whenClientOnlyAcceptsXml_returns406() {
        client.get().uri("/api/orders/7")
                .accept(MediaType.APPLICATION_XML)
                .exchange()
                .expectStatus().isEqualTo(406);

        verifyNoInteractions(orderService);
    }

    // --- GET /api/orders?customerEmail= ---------------------------------

    @Test
    void findRecentOrders_returnsJsonArrayInServiceOrder() {
        when(orderService.findRecentOrdersForCustomer("alice@example.com")).thenReturn(List.of(
                order(2L, "ORD-2", OrderStatus.CREATED),
                order(1L, "ORD-1", OrderStatus.SHIPPED)));

        client.get().uri(uri -> uri.path("/api/orders").queryParam("customerEmail", "alice@example.com").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].orderNumber").isEqualTo("ORD-2")
                .jsonPath("$[1].orderNumber").isEqualTo("ORD-1");
    }

    @Test
    void findRecentOrders_whenCustomerHasNone_returnsEmptyArray() {
        when(orderService.findRecentOrdersForCustomer("nobody@example.com")).thenReturn(List.of());

        client.get().uri("/api/orders?customerEmail=nobody@example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]", JsonCompareMode.STRICT);
    }

    @Test
    void findRecentOrders_withoutCustomerEmailParam_returns400() {
        client.get().uri("/api/orders")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

        verifyNoInteractions(orderService);
    }

    // --- POST /api/orders/{id}/pay|ship|cancel --------------------------

    @Test
    void markAsPaid_returns200WithUpdatedStatus() {
        when(orderService.markAsPaid(7L)).thenReturn(order(7L, "ORD-7", OrderStatus.PAID));

        client.post().uri("/api/orders/7/pay")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PAID");
    }

    @Test
    void markAsPaid_whenTransitionNotAllowed_returns409ProblemDetail() {
        when(orderService.markAsPaid(7L))
                .thenThrow(new InvalidOrderStateException("Cannot mark order 7 as paid from status SHIPPED"));

        client.post().uri("/api/orders/7/pay")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().json("""
                        {
                          "title": "Invalid order state",
                          "status": 409,
                          "detail": "Cannot mark order 7 as paid from status SHIPPED",
                          "instance": "/api/orders/7/pay"
                        }
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void ship_returns200WithUpdatedStatusAndTrackingNumber() {
        Order shipped = order(7L, "ORD-7", OrderStatus.SHIPPED);
        shipped.setTrackingNumber("TRACK-7");
        when(orderService.ship(7L)).thenReturn(shipped);

        client.post().uri("/api/orders/7/ship")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SHIPPED")
                .jsonPath("$.trackingNumber").isEqualTo("TRACK-7");
    }

    @Test
    void ship_whenCarrierRejects_returns422ProblemDetail() {
        when(orderService.ship(7L)).thenThrow(new ShipmentRejectedException("ORD-7", 400, null));

        client.post().uri("/api/orders/7/ship")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().json("""
                        {
                          "title": "Shipment rejected by carrier",
                          "status": 422,
                          "detail": "Shipping carrier rejected the shipment for order 'ORD-7' (HTTP 400)",
                          "instance": "/api/orders/7/ship"
                        }
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void ship_whenCarrierUnavailable_returns503ProblemDetail() {
        when(orderService.ship(7L))
                .thenThrow(new ShippingUnavailableException("Shipping carrier answered HTTP 503 for order 'ORD-7'", null));

        client.post().uri("/api/orders/7/ship")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Shipping carrier unavailable")
                .jsonPath("$.detail").isEqualTo("Shipping carrier answered HTTP 503 for order 'ORD-7'");
    }

    @Test
    void cancel_returns200WithUpdatedStatus() {
        when(orderService.cancel(7L)).thenReturn(order(7L, "ORD-7", OrderStatus.CANCELLED));

        client.post().uri("/api/orders/7/cancel")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void cancel_whenOrderNotFound_returns404() {
        when(orderService.cancel(99L)).thenThrow(new OrderNotFoundException(99L));

        client.post().uri("/api/orders/99/cancel")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.title").isEqualTo("Order not found");
    }

    @Test
    void pay_withGetInsteadOfPost_returns405() {
        client.get().uri("/api/orders/7/pay")
                .exchange()
                .expectStatus().isEqualTo(405);

        verifyNoInteractions(orderService);
    }

    /**
     * Builds an {@link Order} as if it had been loaded from the database. The id is normally
     * generated by Postgres and has no setter, so it's set reflectively — acceptable in a test
     * fixture, and it keeps the entity from growing a setter just for tests.
     */
    private static Order order(Long id, String orderNumber, OrderStatus status) {
        Order order = new Order(orderNumber, "alice@example.com", CREATED_AT);
        order.setStatus(status);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
