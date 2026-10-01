package com.example.testinglearning.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * JSON body for {@code POST /api/orders}. A separate DTO rather than binding straight onto the
 * {@link Order} entity, so the API contract doesn't change every time the persistence model does.
 *
 * <p>Bean Validation runs before the controller method (see docs/testing-strategy.md §9). Every
 * constraint has an explicit {@code message}: those strings are part of our 400 error contract,
 * and Hibernate Validator's built-in messages are translated into the JVM's default locale, so
 * the same request would get a different body on an Italian machine than on an English one.
 */
public record PlaceOrderRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 50, message = "must be at most 50 characters")
        String orderNumber,

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        String customerEmail,

        @NotEmpty(message = "must contain at least one item")
        @Size(max = 100, message = "must contain at most 100 items")
        List<@Valid @NotNull(message = "must not be null") Item> items) {

    public record Item(
            @NotBlank(message = "must not be blank")
            @Size(max = 200, message = "must be at most 200 characters")
            String productName,

            // Integer, not int: Jackson 3 rejects a missing primitive field
            // (FAIL_ON_NULL_FOR_PRIMITIVES is on by default) while parsing the JSON, so a missing
            // quantity would become a generic "Bad Request" instead of an entry in our errors array.
            @NotNull(message = "must not be null")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 1000, message = "must be at most 1000")
            Integer quantity,

            @NotNull(message = "must not be null")
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places")
            BigDecimal unitPrice) {

        OrderItem toOrderItem() {
            return new OrderItem(productName, quantity, unitPrice);
        }
    }

    List<OrderItem> toOrderItems() {
        // null is passed through on purpose: the service still owns the "at least one item" rule
        // for callers that don't come in over HTTP (and so skip Bean Validation).
        return items == null ? null : items.stream().map(Item::toOrderItem).toList();
    }
}
