package ru.potekhincode.inventory.exception;

public class StockNotFoundException extends RuntimeException {
    public StockNotFoundException(String productId) {
        super("Позиция склада не найдена: " + productId);
    }
}
