package ru.potekhincode.inventory.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.InventoryReserved;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.avro.ReservedItem;
import ru.potekhincode.inventory.exception.InsufficientStockException;
import ru.potekhincode.inventory.service.InventoryService;
import ru.potekhincode.inventory.service.ReservationItem;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventProducer eventProducer;

    @KafkaListener(topics = "order.created", containerFactory = "kafkaListenerContainerFactory")
    public void handleOrderCreated(OrderCreated event) {
        String orderId = event.getOrderId().toString();
        List<ReservationItem> items = event.getItems().stream()
                .map(i -> new ReservationItem(i.getProductId().toString(), i.getQuantity()))
                .toList();
        log.info("Received order.created: orderId={}, items={}", orderId, items.size());

        InventoryReserved result;
        try {
            inventoryService.reserve(orderId, items);
            result = buildReserved(event, true, null);
        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock for orderId={}: {}", orderId, e.getMessage());
            result = buildReserved(event, false, e.getMessage());
        }

        eventProducer.publishInventoryReserved(result);
    }

    private InventoryReserved buildReserved(OrderCreated event, boolean success, String reason) {
        List<ReservedItem> items = event.getItems().stream()
                .map(i -> ReservedItem.newBuilder()
                        .setProductId(i.getProductId())
                        .setQuantity(i.getQuantity())
                        .build())
                .toList();
        return InventoryReserved.newBuilder()
                .setOrderId(event.getOrderId())
                .setItems(items)
                .setSuccess(success)
                .setReason(reason)
                .setTimestamp(Instant.now())
                .build();
    }
}