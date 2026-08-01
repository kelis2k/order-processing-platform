package ru.potekhincode.inventory.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.potekhincode.inventory.exception.InsufficientStockException;
import ru.potekhincode.inventory.service.InventoryService;

@GrpcService
@RequiredArgsConstructor
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final InventoryService inventoryService;

    @Override
    public void checkAvailability(CheckAvailabilityRequest request,
                                  StreamObserver<CheckAvailabilityResponse> responseObserver) {
        boolean available = inventoryService.checkAvailability(request.getProductId(), request.getQuantity());
        int quantity = inventoryService.getAvailableQuantity(request.getProductId());

        responseObserver.onNext(CheckAvailabilityResponse.newBuilder()
                .setAvailable(available)
                .setAvailableQuantity(quantity)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void reserve(ReserveRequest request,
                        StreamObserver<ReserveResponse> responseObserver) {
        try {
            inventoryService.reserve(request.getOrderId(), request.getProductId(), request.getQuantity());
            responseObserver.onNext(ReserveResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Reserved successfully")
                    .build());
        } catch (InsufficientStockException e) {
            responseObserver.onNext(ReserveResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage(e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

}
