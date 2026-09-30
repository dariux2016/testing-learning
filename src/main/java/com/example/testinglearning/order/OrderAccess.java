package com.example.testinglearning.order;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Ownership check used from SpEL as {@code @orderAccess.isOwner(#orderId, authentication)}.
 *
 * <p>It exists for methods that <em>change</em> an order, like {@link OrderService#cancel}. They
 * need the check <em>before</em> the method runs, when all we have is an id. A
 * {@code @PostAuthorize} would check only after the order had already been cancelled and saved.
 *
 * <p>An order that doesn't exist counts as "not yours". A customer then gets 403 for someone
 * else's order and for a missing one alike, so they can't probe which order ids exist.
 */
@Component("orderAccess")
public class OrderAccess {

    private final OrderRepository orderRepository;

    public OrderAccess(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public boolean isOwner(Long orderId, Authentication authentication) {
        return orderRepository.findById(orderId)
                .map(order -> order.getCustomerEmail().equals(authentication.getName()))
                .orElse(false);
    }
}
