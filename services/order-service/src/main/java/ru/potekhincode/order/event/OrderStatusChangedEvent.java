package ru.potekhincode.order.event;

import ru.potekhincode.order.model.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderStatusChangedEvent(
        UUID orderId,
        OrderStatus status,
        OffsetDateTime occurredAt
) {
}
