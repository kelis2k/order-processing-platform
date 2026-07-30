package ru.potekhincode.order.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface ProductCatalogClient {
    Map<String, BigDecimal> getPrices(List<String> productIds);
}
