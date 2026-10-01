package com.example.testinglearning.order;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Ownership rules live here as method security (enabled by {@code MethodSecurityConfig}; see
 * docs/testing-strategy.md §8): STAFF may act on any order, a CUSTOMER only on their own, where
 * "own" means {@code customerEmail} equals {@code authentication.name} (the token's email claim).
 * {@link #markAsPaid} and {@link #ship} are deliberately <em>not</em> annotated.
 * {@code PaymentConfirmationListener} calls {@code markAsPaid} from a Kafka thread with no logged-in
 * user, and their HTTP endpoints are already STAFF-only in {@code SecurityConfig}.
 */
@Service
public class OrderService {

    /** How long after placing it a customer may still cancel an order (inclusive). */
    static final Duration CANCELLATION_WINDOW = Duration.ofMinutes(30);

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ResilientShippingClient shippingClient;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher,
                        ResilientShippingClient shippingClient, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.shippingClient = shippingClient;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('STAFF') or #customerEmail == authentication.name")
    public Order placeOrder(String orderNumber, String customerEmail, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order must have at least one item");
        }

        Order order = new Order(orderNumber, customerEmail, Instant.now(clock));
        items.forEach(order::addItem);

        Order saved;
        try {
            saved = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateOrderNumberException(orderNumber, e);
        }

        // Only publish once the order is durably persisted - never on the duplicate-order-number
        // failure path above.
        orderEventPublisher.publishOrderPlaced(saved);
        return saved;
    }

    // Post, not Pre: whose order it is is only known after loading it. That's fine for a read.
    @PostAuthorize("hasRole('STAFF') or returnObject.customerEmail == authentication.name")
    public Order getOrder(Long orderId) {
        return getOrThrow(orderId);
    }

    public Order markAsPaid(Long orderId) {
        Order order = getOrThrow(orderId);
        // Idempotent on purpose. Kafka delivers at least once, so the same PaymentConfirmedEvent
        // can arrive twice (a redelivery after a rebalance, a producer retry). The payment has
        // already been recorded, so a duplicate is a no-op, not an error that gets retried and
        // ends up on the dead-letter topic. SHIPPED implies PAID, so it counts too. A payment for
        // a CANCELLED order is still an error: money really arrived and someone must refund it.
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.SHIPPED) {
            return order;
        }
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new InvalidOrderStateException(
                    "Cannot mark order " + orderId + " as paid from status " + order.getStatus());
        }
        order.setStatus(OrderStatus.PAID);
        return orderRepository.save(order);
    }

    public Order ship(Long orderId) {
        Order order = getOrThrow(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new InvalidOrderStateException(
                    "Cannot ship order " + orderId + " from status " + order.getStatus());
        }
        // Book with the carrier *before* touching the order. If the carrier call throws, the order
        // is left PAID and unsaved, so shipping it can simply be tried again later.
        String trackingNumber = shippingClient.createShipment(order);
        order.setTrackingNumber(trackingNumber);
        order.setStatus(OrderStatus.SHIPPED);
        return orderRepository.save(order);
    }

    // Pre with a lookup bean, not @PostAuthorize: this method changes data, and a post-check would
    // run only after the order had already been cancelled and saved.
    @PreAuthorize("hasRole('STAFF') or @orderAccess.isOwner(#orderId, authentication)")
    public Order cancel(Long orderId) {
        Order order = getOrThrow(orderId);
        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Cannot cancel order " + orderId + " from status " + order.getStatus());
        }
        // Inclusive: at exactly createdAt + 30 minutes cancelling is still allowed; one instant
        // later it isn't. OrderServiceUnitTest pins both sides with a fixed Clock.
        Instant deadline = order.getCreatedAt().plus(CANCELLATION_WINDOW);
        if (Instant.now(clock).isAfter(deadline)) {
            throw new CancellationWindowExpiredException(orderId, CANCELLATION_WINDOW);
        }
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    @PreAuthorize("hasRole('STAFF') or #email == authentication.name")
    public List<Order> findRecentOrdersForCustomer(String email) {
        return orderRepository.findRecentOrdersForCustomer(email);
    }

    private Order getOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
