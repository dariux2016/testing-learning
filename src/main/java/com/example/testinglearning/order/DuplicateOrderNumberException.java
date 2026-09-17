package com.example.testinglearning.order;

public class DuplicateOrderNumberException extends RuntimeException {

    public DuplicateOrderNumberException(String orderNumber, Throwable cause) {
        super("An order with number '" + orderNumber + "' already exists", cause);
    }
}
