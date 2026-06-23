package ru.potekhincode.order.outbox;

import java.util.List;

public record OrderCreatedPayload(
        String orderId,
        String userId,
        List<Item> items,
        long timestamp
) {
    public record Item(String productId, int quantity) {
    }
}
