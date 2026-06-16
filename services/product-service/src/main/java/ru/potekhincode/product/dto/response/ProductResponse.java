package ru.potekhincode.product.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class ProductResponse {
    private String id;
    private String name;
    private String description;
    private String category;
    private BigDecimal price;
    private boolean published;
    private String imageUrl;
    private Instant createdAt;
    private Instant updatedAt;
}
