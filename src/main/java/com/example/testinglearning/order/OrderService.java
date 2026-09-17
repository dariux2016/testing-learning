package com.example.testinglearning.order;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order placeOrder(String orderNumber, String customerEmail, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order must have at least one item");
        }

        Order order = new Order(orderNumber, customerEmail, Instant.now());
        items.forEach(order::addItem);

        try {
            return orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateOrderNumberException(orderNumber, e);
        }
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
