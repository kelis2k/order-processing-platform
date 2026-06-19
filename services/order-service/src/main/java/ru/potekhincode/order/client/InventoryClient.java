package ru.potekhincode.order.client;

import ru.potekhincode.order.dto.request.OrderItemRequest;

import java.util.List;

public interface InventoryClient {
    List<UnavailableItem> checkAvailability(List<OrderItemRequest> items);
}
