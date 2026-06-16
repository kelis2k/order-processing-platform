package ru.potekhincode.product.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Document(collection = "products")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Product {

    @Id
    private String id;

    @TextIndexed(weight = 3)
    private String name;

    @TextIndexed
    private String description;

    private String category;
    private BigDecimal price;
    private boolean published;
    private String imageUrl;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
