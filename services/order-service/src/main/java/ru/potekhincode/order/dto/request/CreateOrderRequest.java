package ru.potekhincode.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "заказ должен содержать хотя бы одну позицию")
        @Valid
        List<OrderItemRequest> items
) {
}
