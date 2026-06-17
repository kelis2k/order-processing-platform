package ru.potekhincode.order.exception;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID id) {
        super("Заказ не найден: " + id);
    }
}
