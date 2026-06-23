package ru.potekhincode.inventory.service;

public interface InventoryService {
    boolean checkAvailability(String productId, int quantity);
    int getAvailableQuantity(String productId);
    void reserve(String orderId, String productId, int quantity);
    void reserve(String orderId, java.util.List<ReservationItem> items);
    void release(String orderId, String productId, int quantity);
}
