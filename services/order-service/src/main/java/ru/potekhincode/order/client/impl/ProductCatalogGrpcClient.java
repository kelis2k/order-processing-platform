package ru.potekhincode.order.client.impl;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import ru.potekhincode.order.client.ProductCatalogClient;
import ru.potekhincode.product.grpc.GetPricesRequest;
import ru.potekhincode.product.grpc.GetPricesResponse;
import ru.potekhincode.product.grpc.ProductCatalogServiceGrpc;
import ru.potekhincode.product.grpc.ProductPrice;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProductCatalogGrpcClient implements ProductCatalogClient {

    @GrpcClient("product")
    private ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub stub;

    @Override
    public Map<String, BigDecimal> getPrices(List<String> productIds) {
        GetPricesResponse response = stub.getPrices(
                GetPricesRequest.newBuilder().addAllProductIds(productIds).build()
        );

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (ProductPrice price : response.getPricesList()) {
            if (price.getFound()) {
                prices.put(price.getProductId(), new BigDecimal(price.getPrice()));
            }
        }
        return prices;
    }
}
