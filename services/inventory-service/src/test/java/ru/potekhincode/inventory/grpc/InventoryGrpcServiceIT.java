package ru.potekhincode.inventory.grpc;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.potekhincode.inventory.AbstractIntegrationTest;
import ru.potekhincode.inventory.model.Inventory;
import ru.potekhincode.inventory.repository.InventoryRepository;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryGrpcServiceIT extends AbstractIntegrationTest {

    private static final String PRODUCT_ID = "prod-it-grpc-00000001";
    private static final String ORDER_ID = "order-grpc-1";

    @Autowired
    private InventoryRepository inventoryRepository;

    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        inventoryRepository.save(Inventory.builder()
                .productId(PRODUCT_ID)
                .available(10)
                .reserved(0)
                .version(0)
                .build());

        channel = InProcessChannelBuilder.forName(GRPC_IN_PROCESS_NAME).directExecutor().build();
        stub = InventoryServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void checkAvailabilityReturnsCurrentStock() {
        CheckAvailabilityResponse response = stub.checkAvailability(CheckAvailabilityRequest.newBuilder()
                .setProductId(PRODUCT_ID)
                .setQuantity(5)
                .build());

        assertThat(response.getAvailable()).isTrue();
        assertThat(response.getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    void reserveDecrementsAvailableAndIncrementsReserved() {
        ReserveResponse response = stub.reserve(ReserveRequest.newBuilder()
                .setOrderId(ORDER_ID)
                .setProductId(PRODUCT_ID)
                .setQuantity(4)
                .build());

        assertThat(response.getSuccess()).isTrue();
        Inventory updated = inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow();
        assertThat(updated.getAvailable()).isEqualTo(6);
        assertThat(updated.getReserved()).isEqualTo(4);
    }

    @Test
    void reserveFailsWhenStockInsufficient() {
        ReserveResponse response = stub.reserve(ReserveRequest.newBuilder()
                .setOrderId(ORDER_ID)
                .setProductId(PRODUCT_ID)
                .setQuantity(999)
                .build());

        assertThat(response.getSuccess()).isFalse();
        Inventory unchanged = inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow();
        assertThat(unchanged.getAvailable()).isEqualTo(10);
        assertThat(unchanged.getReserved()).isZero();
    }

}
