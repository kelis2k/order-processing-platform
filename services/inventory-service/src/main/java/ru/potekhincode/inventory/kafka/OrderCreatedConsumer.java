package ru.potekhincode.inventory.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.InventoryReserved;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.inventory.exception.InsufficientStockException;
import ru.potekhincode.inventory.kafka.InventoryEventProducer;
import ru.potekhincode.inventory.service.InventoryService;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventProducer eventProducer;

    @KafkaListener(topics = "order.created", containerFactory = "kafkaListenerContainerFactory")
    public void handleOrderCreated(OrderCreated event) {
        log.info("Received order.created: orderId={}, productId={}, quantity={}",
                event.getOrderId(), event.getProductId(), event.getQuantity());

        InventoryReserved result;
        try {
            inventoryService.reserve(
                    event.getOrderId(),
                    event.getProductId(),
                    event.getQuantity()
            );
            result = InventoryReserved.newBuilder()
                    .setOrderId(event.getOrderId())
                    .setProductId(event.getProductId())
                    .setQuantity(event.getQuantity())
                    .setSuccess(true)
                    .setReason(null)
                    .setTimestamp(Instant.now())
                    .build();
        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock for orderId={}: {}", event.getOrderId(), e.getMessage());
            result = InventoryReserved.newBuilder()
                    .setOrderId(event.getOrderId())
                    .setProductId(event.getProductId())
                    .setQuantity(event.getQuantity())
                    .setSuccess(false)
                    .setReason(e.getMessage())
                    .setTimestamp(Instant.now())
                    .build();
        }

        eventProducer.publishInventoryReserved(result);
    }
}