package ru.potekhincode.order.exception;

import java.util.UUID;

public class OrderAccessDeniedException extends RuntimeException {

    public OrderAccessDeniedException(UUID orderId) {
        super("Order does not belong to the caller: " + orderId);
    }
}