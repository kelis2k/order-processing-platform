package ru.potekhincode.inventory.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.OrderStatusChanged;
import ru.potekhincode.inventory.service.InventoryService;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangedConsumer {

    private static final String SHIPPED = "SHIPPED";

    private final InventoryService inventoryService;

    @KafkaListener(topics = "order.status-changed", containerFactory = "kafkaListenerContainerFactory")
    public void handle(OrderStatusChanged event) {
        String status = event.getStatus().toString();
        if (!SHIPPED.equals(status)) {
            return;
        }

        String orderId = event.getOrderId().toString();
        log.info("Received order.status-changed SHIPPED: orderId={}, списываем резерв", orderId);
        inventoryService.commitReservation(orderId);
    }
}
