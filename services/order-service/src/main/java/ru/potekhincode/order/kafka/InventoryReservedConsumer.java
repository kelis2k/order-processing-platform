package ru.potekhincode.order.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.InventoryReserved;
import ru.potekhincode.order.service.OrderService;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservedConsumer {

    private final OrderService orderService;

    @KafkaListener(topics = "inventory.reserved", containerFactory = "kafkaListenerContainerFactory")
    public void handle(InventoryReserved event) {
        UUID orderId = UUID.fromString(event.getOrderId().toString());
        boolean success = event.getSuccess();
        log.info("Received inventory.reserved: orderId={}, success={}, reason={}",
                orderId, success, event.getReason());

        String reason = event.getReason() == null ? null : event.getReason().toString();

        orderService.onInventoryReserved(orderId, success, reason);
    }
}