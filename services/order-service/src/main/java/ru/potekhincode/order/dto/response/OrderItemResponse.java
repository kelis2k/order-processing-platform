package ru.potekhincode.order.dto.response;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        String productId,
        Integer quantity,
        BigDecimal unitPrice
) {
}