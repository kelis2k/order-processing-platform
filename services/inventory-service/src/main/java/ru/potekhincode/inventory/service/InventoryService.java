package ru.potekhincode.inventory.service;

public interface InventoryService {
    ru.potekhincode.inventory.dto.StockResponse setStock(String productId, int available);
    ru.potekhincode.inventory.dto.StockResponse getStock(String productId);
    boolean checkAvailability(String productId, int quantity);
    int getAvailableQuantity(String productId);
    void reserve(String orderId, String productId, int quantity);
    void reserve(String orderId, java.util.List<ReservationItem> items);
    void commitReservation(String orderId);
}
