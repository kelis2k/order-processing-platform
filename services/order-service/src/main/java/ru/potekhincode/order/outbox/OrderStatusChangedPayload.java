package ru.potekhincode.order.outbox;

public record OrderStatusChangedPayload(
        String orderId,
        String userId,
        String status,
        String reason,
        long timestamp
) {

}
