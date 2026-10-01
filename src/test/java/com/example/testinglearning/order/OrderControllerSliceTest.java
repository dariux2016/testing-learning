package com.example.testinglearning.order;

import com.example.testinglearning.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
    void placeOrder_whenServiceThrowsIllegalArgument_returns400ProblemDetail() {
        // The body is valid, so Bean Validation lets it through. This pins down how a rule the
        // *service* enforces (IllegalArgumentException) reaches the client.
        when(orderService.placeOrder(eq("ORD-2"), eq("alice@example.com"), anyList()))
                .thenThrow(new IllegalArgumentException("An order must have at least one item"));

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("\"ORD-2\"", "\"alice@example.com\"", "[" + item("\"Widget\"", "1", "9.99") + "]"))
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

    // --- POST /api/orders: Bean Validation (§9) -------------------------------

    /**
     * One row per broken rule: a body that's valid except for exactly one thing, and the single
     * {@code errors} entry it must produce. Boundary values come in pairs with
     * {@link #boundaryValuesJustInsideTheRules()}: e.g. quantity 0 here, 1 there.
     */
    static Stream<Arguments> invalidPlaceOrderBodies() {
        String number = "\"ORD-1\"";
        String email = "\"alice@example.com\"";
        String validItem = item("\"Widget\"", "1", "9.99");
        String validItems = "[" + validItem + "]";
        return Stream.of(
                arguments("orderNumber missing", body(null, email, validItems),
                        "orderNumber", "must not be blank"),
                arguments("orderNumber blank", body("\"   \"", email, validItems),
                        "orderNumber", "must not be blank"),
                arguments("orderNumber 51 chars", body("\"" + "X".repeat(51) + "\"", email, validItems),
                        "orderNumber", "must be at most 50 characters"),
                arguments("customerEmail missing", body(number, null, validItems),
                        "customerEmail", "must not be blank"),
                arguments("customerEmail malformed", body(number, "\"not-an-email\"", validItems),
                        "customerEmail", "must be a well-formed email address"),
                arguments("items missing", body(number, email, null),
                        "items", "must contain at least one item"),
                arguments("items empty", body(number, email, "[]"),
                        "items", "must contain at least one item"),
                arguments("101 items", body(number, email, "[" + String.join(",", Collections.nCopies(101, validItem)) + "]"),
                        "items", "must contain at most 100 items"),
                arguments("null item", body(number, email, "[null]"),
                        "items[0]", "must not be null"),
                arguments("productName blank", body(number, email, "[" + item("\"\"", "1", "9.99") + "]"),
                        "items[0].productName", "must not be blank"),
                arguments("quantity 0", body(number, email, "[" + item("\"Widget\"", "0", "9.99") + "]"),
                        "items[0].quantity", "must be at least 1"),
                arguments("quantity missing", body(number, email, "[" + item("\"Widget\"", null, "9.99") + "]"),
                        "items[0].quantity", "must not be null"),
                arguments("quantity 1001", body(number, email, "[" + item("\"Widget\"", "1001", "9.99") + "]"),
                        "items[0].quantity", "must be at most 1000"),
                arguments("unitPrice missing", body(number, email, "[" + item("\"Widget\"", "1", null) + "]"),
                        "items[0].unitPrice", "must not be null"),
                arguments("unitPrice 0.00", body(number, email, "[" + item("\"Widget\"", "1", "0.00") + "]"),
                        "items[0].unitPrice", "must be at least 0.01"),
                arguments("unitPrice 3 decimals", body(number, email, "[" + item("\"Widget\"", "1", "9.999") + "]"),
                        "items[0].unitPrice", "must have at most 2 decimal places"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPlaceOrderBodies")
    void placeOrder_withOneInvalidField_returns400ListingExactlyThatField(
            String scenario, String body, String field, String message) {
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Validation failed")
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].field").isEqualTo(field)
                .jsonPath("$.errors[0].message").isEqualTo(message);

        // Validation runs before the controller method, so the service is never reached.
        verifyNoInteractions(orderService);
    }

    static Stream<Arguments> boundaryValuesJustInsideTheRules() {
        String number = "\"ORD-1\"";
        String email = "\"alice@example.com\"";
        String validItem = item("\"Widget\"", "1", "9.99");
        return Stream.of(
                arguments("orderNumber exactly 50 chars", body("\"" + "X".repeat(50) + "\"", email, "[" + validItem + "]")),
                arguments("quantity 1", body(number, email, "[" + item("\"Widget\"", "1", "9.99") + "]")),
                arguments("quantity 1000", body(number, email, "[" + item("\"Widget\"", "1000", "9.99") + "]")),
                arguments("unitPrice 0.01", body(number, email, "[" + item("\"Widget\"", "1", "0.01") + "]")),
                arguments("exactly 100 items", body(number, email, "[" + String.join(",", Collections.nCopies(100, validItem)) + "]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryValuesJustInsideTheRules")
    void placeOrder_withBoundaryValuesJustInsideTheRules_isAccepted(String scenario, String body) {
        when(orderService.placeOrder(anyString(), anyString(), anyList()))
                .thenReturn(order(1L, "ORD-1", OrderStatus.CREATED));

        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void placeOrder_withSeveralInvalidFields_returnsAllOfThemSortedByField() {
        // 0.001 breaks two rules at once (too small, too many decimals); both are reported.
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("\"\"", "\"not-an-email\"", "[" + item("\"Widget\"", "0", "0.001") + "]"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().json("""
                        {
                          "title": "Validation failed",
                          "status": 400,
                          "detail": "The request has 5 invalid field(s)",
                          "instance": "/api/orders",
                          "errors": [
                            { "field": "customerEmail",      "message": "must be a well-formed email address" },
                            { "field": "items[0].quantity",  "message": "must be at least 1" },
                            { "field": "items[0].unitPrice", "message": "must be at least 0.01" },
                            { "field": "items[0].unitPrice", "message": "must have at most 2 decimal places" },
                            { "field": "orderNumber",        "message": "must not be blank" }
                          ]
                        }
                        """, JsonCompareMode.STRICT);

        verifyNoInteractions(orderService);
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
    void findRecentOrders_withMalformedEmailParam_returns400ValidationProblem() {
        // A constraint on a @RequestParam goes through Spring's method validation, a different
        // exception from a @Valid body (see OrderExceptionHandler). Same error shape either way.
        client.get().uri("/api/orders?customerEmail=not-an-email")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Validation failed")
                .jsonPath("$.errors[0].field").isEqualTo("customerEmail")
                .jsonPath("$.errors[0].message").isEqualTo("must be a well-formed email address");

        verifyNoInteractions(orderService);
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
                .thenThrow(new InvalidOrderStateException("Cannot mark order 7 as paid from status CANCELLED"));

        client.post().uri("/api/orders/7/pay")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().json("""
                        {
                          "title": "Invalid order state",
                          "status": 409,
                          "detail": "Cannot mark order 7 as paid from status CANCELLED",
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
    void cancel_afterTheWindow_returns409ProblemDetail() {
        when(orderService.cancel(7L)).thenThrow(new CancellationWindowExpiredException(7L, Duration.ofMinutes(30)));

        client.post().uri("/api/orders/7/cancel")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Cancellation window expired")
                .jsonPath("$.detail").isEqualTo("Order 7 can no longer be cancelled: the 30-minute cancellation window has passed");
    }

    @Test
    void ship_whenAnotherRequestChangedTheOrderMeanwhile_returns409ProblemDetail() {
        when(orderService.ship(7L)).thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 7L));

        client.post().uri("/api/orders/7/ship")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().json("""
                        {
                          "title": "Concurrent modification",
                          "status": 409,
                          "detail": "The order was changed by another request at the same time; reload it and try again",
                          "instance": "/api/orders/7/ship"
                        }
                        """, JsonCompareMode.STRICT);
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
    /** Builds a place-order body. Each argument is raw JSON; {@code null} leaves the field out. */
    private static String body(String orderNumberJson, String customerEmailJson, String itemsJson) {
        List<String> fields = new ArrayList<>();
        if (orderNumberJson != null) fields.add("\"orderNumber\": " + orderNumberJson);
        if (customerEmailJson != null) fields.add("\"customerEmail\": " + customerEmailJson);
        if (itemsJson != null) fields.add("\"items\": " + itemsJson);
        return "{ " + String.join(", ", fields) + " }";
    }

    /** Builds one item object. Each argument is raw JSON; {@code null} leaves the field out. */
    private static String item(String productNameJson, String quantityJson, String unitPriceJson) {
        List<String> fields = new ArrayList<>();
        if (productNameJson != null) fields.add("\"productName\": " + productNameJson);
        if (quantityJson != null) fields.add("\"quantity\": " + quantityJson);
        if (unitPriceJson != null) fields.add("\"unitPrice\": " + unitPriceJson);
        return "{ " + String.join(", ", fields) + " }";
    }

    private static Order order(Long id, String orderNumber, OrderStatus status) {
        Order order = new Order(orderNumber, "alice@example.com", CREATED_AT);
        order.setStatus(status);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
