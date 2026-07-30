package ru.potekhincode.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SetStockRequest(
        @NotNull(message = "available is required")
        @Min(value = 0, message = "available must be non-negative")
        Integer available
) {
}
