package ru.potekhincode.order.exception;

import ru.potekhincode.order.model.OrderStatus;

public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Недопустимый переход статуса: " + from + " → " + to);
    }
}