package com.example.testinglearning.order;

import com.example.testinglearning.security.MethodSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the {@code @PreAuthorize}/{@code @PostAuthorize} ownership rules on {@link OrderService}
 * directly (see docs/testing-strategy.md §8).
 *
 * <p>This is the smallest context in which method security exists: {@code @SpringJUnitConfig} with
 * just {@link MethodSecurityConfig}, {@link OrderService} and {@link OrderAccess}. There's no Spring
 * Boot, no web layer, no database. The annotations only work through a Spring proxy, which is why
 * {@link OrderServiceUnitTest} (a plain {@code new OrderService(...)}) can't test them. The
 * collaborators are {@code @MockitoBean}s, and {@code @WithMockUser} sets the logged-in user.
 * {@code username} plays the role of the token's email claim.
 *
 * <p>A denied call throws {@link AuthorizationDeniedException}. Over HTTP, Spring Security turns
 * that into a 403.
 */
@SpringJUnitConfig({MethodSecurityConfig.class, OrderService.class, OrderAccess.class,
        OrderServiceMethodSecuritySliceTest.FixedClock.class})
class OrderServiceMethodSecuritySliceTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    /** OrderService needs a Clock. Fixed 5 minutes after orderOf()'s createdAt, inside the cancel window. */
    @Configuration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T10:05:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    @MockitoBean
    private ResilientShippingClient shippingClient;

    // --- placeOrder: @PreAuthorize on an argument ----------------------------

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void placeOrder_asCustomerForThemselves_isAllowed() {
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order placed = orderService.placeOrder("ORD-1", ALICE, items());

        assertThat(placed.getCustomerEmail()).isEqualTo(ALICE);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void placeOrder_asCustomerForSomeoneElse_isDeniedBeforeAnythingRuns() {
        assertThatThrownBy(() -> orderService.placeOrder("ORD-1", BOB, items()))
                .isInstanceOf(AuthorizationDeniedException.class);

        // Pre-authorization: the method body never ran, so nothing was saved or published.
        verifyNoInteractions(orderRepository, orderEventPublisher);
    }

    @Test
    @WithMockUser(username = "sam@example.com", roles = "STAFF")
    void placeOrder_asStaffForAnyCustomer_isAllowed() {
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(orderService.placeOrder("ORD-1", BOB, items()).getCustomerEmail()).isEqualTo(BOB);
    }

    // --- getOrder: @PostAuthorize on the return value -------------------------

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void getOrder_asOwner_isAllowed() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderOf(1L, ALICE)));

        assertThat(orderService.getOrder(1L).getCustomerEmail()).isEqualTo(ALICE);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void getOrder_asAnotherCustomer_isDeniedAfterLoading() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderOf(1L, BOB)));

        assertThatThrownBy(() -> orderService.getOrder(1L))
                .isInstanceOf(AuthorizationDeniedException.class);

        // Post-authorization: the method *did* run (the order was loaded), and only its result was
        // withheld. That's acceptable for a read, and it's exactly why cancel() can't work this way.
        verify(orderRepository).findById(1L);
    }

    @Test
    @WithMockUser(username = "sam@example.com", roles = "STAFF")
    void getOrder_asStaff_canReadAnyOrder() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderOf(1L, BOB)));

        assertThat(orderService.getOrder(1L).getCustomerEmail()).isEqualTo(BOB);
    }

    @Test
    void getOrder_withNoAuthenticationAtAll_stillRunsTheMethodButWithholdsTheResult() {
        // No @WithMockUser: the SecurityContext is empty. @PostAuthorize doesn't guard entry, so
        // the method body runs for an anonymous caller, and only the check afterwards fails. Over
        // HTTP the filter chain answers 401 long before this point. The lesson: never rely on
        // @PostAuthorize alone to keep a caller out.
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderOf(1L, ALICE)));

        assertThatThrownBy(() -> orderService.getOrder(1L))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verify(orderRepository).findById(1L);
    }

    // --- findRecentOrdersForCustomer -------------------------------------------

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void findRecentOrders_forOwnEmail_isAllowed() {
        when(orderRepository.findRecentOrdersForCustomer(ALICE)).thenReturn(List.of(orderOf(1L, ALICE)));

        assertThat(orderService.findRecentOrdersForCustomer(ALICE)).hasSize(1);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void findRecentOrders_forSomeoneElsesEmail_isDenied() {
        assertThatThrownBy(() -> orderService.findRecentOrdersForCustomer(BOB))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(orderRepository);
    }

    // --- cancel: @PreAuthorize with a lookup bean --------------------------------

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void cancel_asOwner_isAllowed() {
        Order order = orderOf(1L, ALICE);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        assertThat(orderService.cancel(1L).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void cancel_asAnotherCustomer_isDeniedAndTheOrderIsNeverChanged() {
        Order bobsOrder = orderOf(1L, BOB);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(bobsOrder));

        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(AuthorizationDeniedException.class);

        // The point of checking *before* the method: Bob's order is untouched and nothing saved.
        // With @PostAuthorize this would already be CANCELLED in the database.
        assertThat(bobsOrder.getStatus()).isEqualTo(OrderStatus.CREATED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @WithMockUser(username = ALICE, roles = "CUSTOMER")
    void cancel_asCustomerForMissingOrder_isDeniedRatherThan404() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        // Same answer as "someone else's order", so a customer can't probe which ids exist.
        assertThatThrownBy(() -> orderService.cancel(99L))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @WithMockUser(username = "sam@example.com", roles = "STAFF")
    void cancel_asStaffForMissingOrder_getsTheRealNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancel(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // --- markAsPaid: deliberately unsecured -----------------------------------------

    @Test
    void markAsPaid_withNoAuthenticationAtAll_stillWorks() {
        // The Kafka listener calls this with no logged-in user; securing it would break payments.
        // Its HTTP endpoint is STAFF-only at URL level instead (see SecurityConfig).
        Order order = orderOf(1L, ALICE);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        assertThat(orderService.markAsPaid(1L).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // --- fixtures ------------------------------------------------------------

    private static List<OrderItem> items() {
        return List.of(new OrderItem("Widget", 1, new BigDecimal("9.99")));
    }

    private static Order orderOf(Long id, String customerEmail) {
        Order order = new Order("ORD-" + id, customerEmail, Instant.parse("2026-09-30T10:00:00Z"));
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
