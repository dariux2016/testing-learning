package com.example.testinglearning.order;

import com.example.testinglearning.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Security slice tests for the HTTP layer (see docs/testing-strategy.md §8): who gets 401, 403 or
 * through, per endpoint and role.
 *
 * <p>This is the {@code @WebMvcTest} web slice again, this time with the real
 * {@link SecurityConfig} filter chain imported. {@link OrderService} is a mock, and its
 * {@code @PreAuthorize} rules aren't active here (no {@code MethodSecurityConfig} in this slice).
 * So these tests prove only the URL- and role-level rules. The per-order ownership rules are
 * {@link OrderServiceMethodSecuritySliceTest}'s job.
 *
 * <p>Two ways of authenticating are used on purpose:
 * <ul>
 *   <li>{@code jwt().authorities(...)} from Spring Security Test puts an already-authenticated JWT
 *       straight into the security context, skipping token decoding. It's quick and precise for the
 *       role matrix.</li>
 *   <li>A real {@code Authorization: Bearer ...} header, decoded by a mocked {@link JwtDecoder}.
 *       The token then goes through the actual bearer-token filter <em>and</em>
 *       {@code SecurityConfig}'s Keycloak role converter, which {@code jwt()} would skip.</li>
 * </ul>
 * {@link MockMvcTester} is used here rather than {@code RestTestClient} because Spring Security
 * Test's request post-processors ({@code jwt()}) plug directly into MockMvc requests.
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerSecuritySliceTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderService orderService;

    // Replaces the issuer-uri-based decoder that would otherwise try to reach Keycloak.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // --- 401: no or bad credentials --------------------------------------

    @Test
    void anyEndpoint_withoutToken_returns401WithBearerChallenge() {
        MvcTestResult result = mvc.get().uri("/api/orders/1").exchange();

        assertThat(result).hasStatus(401);
        // Spring Security 7 also advertises where the OAuth 2.0 Protected Resource Metadata
        // (RFC 9728) lives, so clients can discover which authorization server to use.
        assertThat(result).headers().hasHeaderSatisfying(HttpHeaders.WWW_AUTHENTICATE,
                values -> assertThat(values.getFirst())
                        .startsWith("Bearer")
                        .contains("resource_metadata=")
                        .doesNotContain("error="));
        verifyNoInteractions(orderService);
    }

    @Test
    void placeOrder_withoutToken_returns401() {
        assertThat(mvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PLACE_ORDER_BODY)
                .exchange())
                .hasStatus(401);
        verifyNoInteractions(orderService);
    }

    @Test
    void anyEndpoint_withTokenTheDecoderRejects_returns401InvalidToken() {
        // Expired, badly signed, wrong issuer: the decoder turns all of these into a JwtException.
        when(jwtDecoder.decode("tampered-token")).thenThrow(new BadJwtException("Signed JWT rejected"));

        MvcTestResult result = mvc.get().uri("/api/orders/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer tampered-token")
                .exchange();

        assertThat(result).hasStatus(401);
        assertThat(result).headers().hasHeaderSatisfying(HttpHeaders.WWW_AUTHENTICATE,
                values -> assertThat(values.getFirst()).contains("error=\"invalid_token\""));
        verifyNoInteractions(orderService);
    }

    // --- 403: authenticated, wrong role -----------------------------------

    @Test
    void pay_asCustomer_returns403() {
        assertThat(mvc.post().uri("/api/orders/7/pay").with(customer()).exchange()).hasStatus(403);
        verifyNoInteractions(orderService);
    }

    @Test
    void ship_asCustomer_returns403() {
        assertThat(mvc.post().uri("/api/orders/7/ship").with(customer()).exchange()).hasStatus(403);
        verifyNoInteractions(orderService);
    }

    @Test
    void ship_withTokenButNoRolesAtAll_returns403() {
        assertThat(mvc.post().uri("/api/orders/7/ship").with(jwt()).exchange()).hasStatus(403);
        verifyNoInteractions(orderService);
    }

    // --- 200: allowed through -------------------------------------------------

    @Test
    void pay_asStaff_returns200() {
        when(orderService.markAsPaid(7L)).thenReturn(order(7L, OrderStatus.PAID));

        assertThat(mvc.post().uri("/api/orders/7/pay").with(staff()).exchange()).hasStatus(200);
    }

    @Test
    void ship_asStaff_returns200() {
        when(orderService.ship(7L)).thenReturn(order(7L, OrderStatus.SHIPPED));

        assertThat(mvc.post().uri("/api/orders/7/ship").with(staff()).exchange()).hasStatus(200);
    }

    @Test
    void getOrder_asCustomer_isLetThroughToTheService() {
        // At URL level any authenticated user may read an order. Whether it's *their* order is
        // decided by @PostAuthorize on OrderService, which this slice deliberately doesn't load.
        when(orderService.getOrder(7L)).thenReturn(order(7L, OrderStatus.CREATED));

        assertThat(mvc.get().uri("/api/orders/7").with(customer()).exchange()).hasStatus(200);
    }

    @Test
    void cancel_asCustomer_isLetThroughToTheService() {
        when(orderService.cancel(7L)).thenReturn(order(7L, OrderStatus.CANCELLED));

        assertThat(mvc.post().uri("/api/orders/7/cancel").with(customer()).exchange()).hasStatus(200);
    }

    // --- stateless: no CSRF token, no session ---------------------------------

    @Test
    void placeOrder_withBearerTokenAndNoCsrfToken_succeedsAndCreatesNoSession() {
        when(orderService.placeOrder(anyString(), anyString(), anyList())).thenReturn(order(1L, OrderStatus.CREATED));

        // No .with(csrf()): with a session-based config this POST would be 403. Here CSRF is off
        // on purpose (see SecurityConfig), and this test pins down that the API really is
        // stateless, so switching CSRF off stays safe.
        MvcTestResult result = mvc.post().uri("/api/orders")
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(PLACE_ORDER_BODY)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).headers().doesNotContainHeader(HttpHeaders.SET_COOKIE);
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // --- the real decoder -> converter path ------------------------------------

    @Test
    void ship_withRealBearerHeaderCarryingKeycloakStaffRole_returns200() {
        when(jwtDecoder.decode("staff-token")).thenReturn(keycloakToken("staff-token", "sam@example.com", "STAFF"));
        when(orderService.ship(anyLong())).thenReturn(order(7L, OrderStatus.SHIPPED));

        assertThat(mvc.post().uri("/api/orders/7/ship")
                .header(HttpHeaders.AUTHORIZATION, "Bearer staff-token")
                .exchange())
                .hasStatus(200);
    }

    @Test
    void ship_withRealBearerHeaderCarryingKeycloakCustomerRole_returns403() {
        when(jwtDecoder.decode("customer-token"))
                .thenReturn(keycloakToken("customer-token", "alice@example.com", "CUSTOMER"));

        assertThat(mvc.post().uri("/api/orders/7/ship")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer-token")
                .exchange())
                .hasStatus(403);
        verifyNoInteractions(orderService);
    }

    // --- fixtures -----------------------------------------------------------

    private static final String PLACE_ORDER_BODY = """
            {
              "orderNumber": "ORD-1",
              "customerEmail": "alice@example.com",
              "items": [ { "productName": "Widget", "quantity": 1, "unitPrice": 9.99 } ]
            }
            """;

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.claim("email", "alice@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor staff() {
        return jwt().jwt(token -> token.claim("email", "sam@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    private static Jwt keycloakToken(String value, String email, String realmRole) {
        return Jwt.withTokenValue(value)
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("email", email)
                .claim("realm_access", Map.of("roles", List.of(realmRole)))
                .build();
    }

    private static Order order(Long id, OrderStatus status) {
        Order order = new Order("ORD-" + id, "alice@example.com", Instant.parse("2026-09-30T10:00:00Z"));
        order.setStatus(status);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
