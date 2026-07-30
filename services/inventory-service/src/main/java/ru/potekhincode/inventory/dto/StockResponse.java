package ru.potekhincode.inventory.dto;

public record StockResponse(String productId, int available, int reserved) {
}
