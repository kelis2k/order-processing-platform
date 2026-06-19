package ru.potekhincode.order.client.impl;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import ru.potekhincode.inventory.grpc.CheckAvailabilityRequest;
import ru.potekhincode.inventory.grpc.CheckAvailabilityResponse;
import ru.potekhincode.inventory.grpc.InventoryServiceGrpc;
import ru.potekhincode.order.client.InventoryClient;
import ru.potekhincode.order.client.UnavailableItem;
import ru.potekhincode.order.dto.request.OrderItemRequest;

import java.util.ArrayList;
import java.util.List;

@Component
public class InventoryGrpcClient implements InventoryClient {

    @GrpcClient("inventory")
    private InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    @Override
    public List<UnavailableItem> checkAvailability(List<OrderItemRequest> items) {
        List<UnavailableItem> unavailable = new ArrayList<>();

        for (OrderItemRequest item : items) {
            CheckAvailabilityResponse resp = stub.checkAvailability(
                    CheckAvailabilityRequest.newBuilder()
                            .setProductId(item.productId())
                            .setQuantity(item.quantity())
                            .build()
            );

            if (!resp.getAvailable()) {
                unavailable.add(new UnavailableItem(
                        item.productId(), item.quantity(), resp.getAvailableQuantity()
                ));
            }
        }

        return unavailable;
    }
}
