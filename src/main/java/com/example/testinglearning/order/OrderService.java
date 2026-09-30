package com.example.testinglearning.order;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ResilientShippingClient shippingClient;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher,
                        ResilientShippingClient shippingClient) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.shippingClient = shippingClient;
    }

    public Order placeOrder(String orderNumber, String customerEmail, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order must have at least one item");
        }

        Order order = new Order(orderNumber, customerEmail, Instant.now());
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

    public Order getOrder(Long orderId) {
        return getOrThrow(orderId);
    }

    public Order markAsPaid(Long orderId) {
        Order order = getOrThrow(orderId);
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

    public Order cancel(Long orderId) {
        Order order = getOrThrow(orderId);
        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Cannot cancel order " + orderId + " from status " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    public List<Order> findRecentOrdersForCustomer(String email) {
        return orderRepository.findRecentOrdersForCustomer(email);
    }

    private Order getOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
