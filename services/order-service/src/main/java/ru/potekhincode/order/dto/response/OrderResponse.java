package ru.potekhincode.order.dto.response;

import ru.potekhincode.order.model.OrderStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String userId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        OffsetDateTime createdAt
) {
}
