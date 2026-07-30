package ru.potekhincode.order.exception;

import java.util.List;

public class ProductNotFoundException extends RuntimeException {

    private final List<String> productIds;

    public ProductNotFoundException(List<String> productIds) {
        super("Товары не найдены в каталоге: " + String.join(", ", productIds));
        this.productIds = productIds;
    }

    public List<String> getProductIds() {
        return productIds;
    }
}
