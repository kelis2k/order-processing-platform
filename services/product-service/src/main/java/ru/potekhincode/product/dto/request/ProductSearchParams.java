package ru.potekhincode.product.dto.request;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSearchParams {
    private String category;
    private BigDecimal priceFrom;
    private BigDecimal priceTo;
    private String text;
    private Boolean published;
}
