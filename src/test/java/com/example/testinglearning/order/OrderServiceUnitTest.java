package com.example.testinglearning.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderService} — no Spring context, {@link OrderRepository} mocked with
 * Mockito (see docs/testing-strategy.md §2). These exercise the service's own branching logic
 * (state-transition rules, validation, exception translation) rather than anything Spring Data
 * or the database does, which is what {@link OrderRepositorySliceTest} already covers.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private ResilientShippingClient shippingClient;

    // "Now" for every test in this class. Orders built by existingOrder() were placed 5 minutes
    // earlier, comfortably inside the cancellation window. The window tests use their own clocks.
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, orderEventPublisher, shippingClient, clock);
    }

    // --- placeOrder -----------------------------------------------------

    @Test
    void placeOrder_withItems_savesAndReturnsTheOrder() {
        List<OrderItem> items = List.of(new OrderItem("Widget", 2, new BigDecimal("9.99")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.placeOrder("ORD-1", "alice@example.com", items);

        assertThat(result.getOrderNumber()).isEqualTo("ORD-1");
        assertThat(result.getCustomerEmail()).isEqualTo("alice@example.com");
        assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(result.getItems()).containsExactlyElementsOf(items);
        // An exact equality on a timestamp is only possible because the clock is fixed.
        assertThat(result.getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderNumber()).isEqualTo("ORD-1");

        // Publishing the OrderPlacedEvent is itself a contract of a successful placeOrder call -
        // downstream consumers rely on it - so it's worth verifying explicitly, not just stubbed.
        verify(orderEventPublisher).publishOrderPlaced(result);
    }

    @Test
    void placeOrder_withNullItems_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> orderService.placeOrder("ORD-2", "alice@example.com", null));

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void placeOrder_withEmptyItems_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> orderService.placeOrder("ORD-3", "alice@example.com", List.of()));

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void placeOrder_whenOrderNumberAlreadyExists_throwsDuplicateOrderNumberException() {
        List<OrderItem> items = List.of(new OrderItem("Widget", 1, new BigDecimal("9.99")));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> orderService.placeOrder("ORD-DUP", "alice@example.com", items))
                .isInstanceOf(DuplicateOrderNumberException.class)
                .hasMessageContaining("ORD-DUP");

        // The order was never durably persisted, so nothing should have been published about it.
        verifyNoInteractions(orderEventPublisher);
    }

    // --- markAsPaid -------------------------------------------------------

    // --- getOrder -------------------------------------------------------

    @Test
    void getOrder_whenFound_returnsTheOrder() {
        Order order = existingOrder(1L, OrderStatus.CREATED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        Order result = orderService.getOrder(1L);

        assertThat(result).isSameAs(order);
    }

    @Test
    void getOrder_whenNotFound_throwsOrderNotFoundException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(99L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void markAsPaid_fromCreated_transitionsToPaid() {
        Order order = existingOrder(1L, OrderStatus.CREATED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        Order result = orderService.markAsPaid(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).save(order);
    }

    @Test
    void markAsPaid_whenOrderNotFound_throwsOrderNotFoundException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.markAsPaid(99L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void markAsPaid_whenAlreadyPaid_isANoOpForDuplicateDeliveries() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        Order result = orderService.markAsPaid(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        // "No-op" means no write at all, not a write of the same value.
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markAsPaid_whenAlreadyShipped_isANoOpToo() {
        // A late duplicate can arrive after the order has moved on. It must not move it back.
        Order order = existingOrder(1L, OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThat(orderService.markAsPaid(1L).getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markAsPaid_whenCancelled_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.markAsPaid(1L))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    // --- ship ---------------------------------------------------------------

    @Test
    void ship_fromPaid_transitionsToShippedAndStoresTrackingNumber() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(shippingClient.createShipment(order)).thenReturn("TRACK-123");
        when(orderRepository.save(order)).thenReturn(order);

        Order result = orderService.ship(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(result.getTrackingNumber()).isEqualTo("TRACK-123");
    }

    @Test
    void ship_whenCarrierUnavailable_leavesOrderPaidAndSavesNothing() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(shippingClient.createShipment(order))
                .thenThrow(new ShippingUnavailableException("carrier down", null));

        assertThatThrownBy(() -> orderService.ship(1L))
                .isInstanceOf(ShippingUnavailableException.class);

        // The contract here is "nothing changed", so it's worth asserting both halves of it.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getTrackingNumber()).isNull();
        verify(orderRepository, never()).save(any());
    }

    @Test
    void ship_whenSaveLosesARaceWithAnotherRequest_letsTheLockingFailurePropagate() {
        // The service doesn't catch or retry this. Retrying blindly could overwrite a cancellation
        // that just happened; the client has to reload and decide.
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(shippingClient.createShipment(order)).thenReturn("TRACK-1");
        when(orderRepository.save(order)).thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 1L));

        assertThatThrownBy(() -> orderService.ship(1L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void ship_whenCarrierRejects_leavesOrderPaidAndSavesNothing() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(shippingClient.createShipment(order))
                .thenThrow(new ShipmentRejectedException("ORD-1", 422, null));

        assertThatThrownBy(() -> orderService.ship(1L))
                .isInstanceOf(ShipmentRejectedException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void ship_fromCreated_throwsInvalidOrderStateExceptionWithoutCallingCarrier() {
        Order order = existingOrder(1L, OrderStatus.CREATED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.ship(1L))
                .isInstanceOf(InvalidOrderStateException.class);

        // Booking a real parcel for an unpaid order would be a costly bug.
        verifyNoInteractions(shippingClient);
    }

    @Test
    void ship_fromCancelled_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.ship(1L))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    // --- cancel --------------------------------------------------------

    @Test
    void cancel_fromCreated_transitionsToCancelled() {
        Order order = existingOrder(1L, OrderStatus.CREATED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        Order result = orderService.cancel(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancel_fromPaid_transitionsToCancelled() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        Order result = orderService.cancel(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    // The window boundary, pinned from both sides. Each test builds its own service with a clock
    // placed exactly where it needs "now" to be. No sleeping, no "roughly 30 minutes".

    @Test
    void cancel_justInsideTheWindow_isAllowed() {
        Instant placedAt = Instant.parse("2026-10-01T09:00:00Z");
        OrderService service = serviceAt(placedAt.plus(Duration.ofMinutes(29)).plusSeconds(59));
        Order order = orderPlacedAt(placedAt);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        assertThat(service.cancel(1L).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancel_exactlyAtTheEndOfTheWindow_isStillAllowed() {
        Instant placedAt = Instant.parse("2026-10-01T09:00:00Z");
        OrderService service = serviceAt(placedAt.plus(Duration.ofMinutes(30)));
        Order order = orderPlacedAt(placedAt);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        assertThat(service.cancel(1L).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancel_oneMillisecondAfterTheWindow_throwsAndSavesNothing() {
        Instant placedAt = Instant.parse("2026-10-01T09:00:00Z");
        OrderService service = serviceAt(placedAt.plus(Duration.ofMinutes(30)).plusMillis(1));
        Order order = orderPlacedAt(placedAt);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(CancellationWindowExpiredException.class)
                .hasMessage("Order 1 can no longer be cancelled: the 30-minute cancellation window has passed");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancel_whenShippedAndOutsideTheWindow_reportsTheStatusProblemFirst() {
        // Both rules are broken; the status check runs first, so its message wins. Pinning the
        // order means a future refactor can't silently change which error clients see.
        Instant placedAt = Instant.parse("2026-10-01T09:00:00Z");
        OrderService service = serviceAt(placedAt.plus(Duration.ofHours(5)));
        Order order = orderPlacedAt(placedAt);
        order.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void cancel_fromShipped_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void cancel_whenAlreadyCancelled_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    // --- findRecentOrdersForCustomer ---------------------------------------

    @Test
    void findRecentOrdersForCustomer_delegatesToRepository() {
        List<Order> expected = List.of(existingOrder(1L, OrderStatus.CREATED));
        when(orderRepository.findRecentOrdersForCustomer("alice@example.com")).thenReturn(expected);

        List<Order> result = orderService.findRecentOrdersForCustomer("alice@example.com");

        assertThat(result).isSameAs(expected);
    }

    private OrderService serviceAt(Instant now) {
        return new OrderService(orderRepository, orderEventPublisher, shippingClient, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Order orderPlacedAt(Instant placedAt) {
        return new Order("ORD-1", "alice@example.com", placedAt);
    }

    private static Order existingOrder(Long id, OrderStatus status) {
        Order order = new Order("ORD-" + id, "alice@example.com", NOW.minus(Duration.ofMinutes(5)));
        order.setStatus(status);
        return order;
    }
}
