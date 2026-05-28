package ru.potekhincode.product.dto.request;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public class UpdateProductRequest {
    private String name;
    private String category;

    @DecimalMin(value = "0.0", inclusive = true, message = "Price must be non-negative")
    private BigDecimal price;

    private String imageUrl;
    private boolean published = false;
}


