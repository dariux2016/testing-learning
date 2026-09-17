package com.example.testinglearning.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository);
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

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderNumber()).isEqualTo("ORD-1");
    }

    @Test
    void placeOrder_withNullItems_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> orderService.placeOrder("ORD-2", "alice@example.com", null));

        verifyNoInteractions(orderRepository);
    }

    @Test
    void placeOrder_withEmptyItems_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> orderService.placeOrder("ORD-3", "alice@example.com", List.of()));

        verifyNoInteractions(orderRepository);
    }

    @Test
    void placeOrder_whenOrderNumberAlreadyExists_throwsDuplicateOrderNumberException() {
        List<OrderItem> items = List.of(new OrderItem("Widget", 1, new BigDecimal("9.99")));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> orderService.placeOrder("ORD-DUP", "alice@example.com", items))
                .isInstanceOf(DuplicateOrderNumberException.class)
                .hasMessageContaining("ORD-DUP");
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
    void markAsPaid_whenAlreadyPaid_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.markAsPaid(1L))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void markAsPaid_whenShipped_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.markAsPaid(1L))
                .isInstanceOf(InvalidOrderStateException.class);
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
    void ship_fromPaid_transitionsToShipped() {
        Order order = existingOrder(1L, OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        Order result = orderService.ship(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void ship_fromCreated_throwsInvalidOrderStateException() {
        Order order = existingOrder(1L, OrderStatus.CREATED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.ship(1L))
                .isInstanceOf(InvalidOrderStateException.class);
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

    private static Order existingOrder(Long id, OrderStatus status) {
        Order order = new Order("ORD-" + id, "alice@example.com", Instant.now());
        order.setStatus(status);
        return order;
    }
}
