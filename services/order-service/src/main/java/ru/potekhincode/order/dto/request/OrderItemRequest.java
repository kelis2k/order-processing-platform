package ru.potekhincode.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record OrderItemRequest(
        @NotBlank(message = "productId обязателен")
        String productId,

        @Positive(message = "quantity должен быть положительным")
        Integer quantity
) { }
