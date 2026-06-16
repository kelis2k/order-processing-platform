package ru.potekhincode.inventory.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.InventoryReserved;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private static final String TOPIC = "inventory.reserved";

    private final KafkaTemplate<String, InventoryReserved> inventoryReservedKafkaTemplate;

    public void publishInventoryReserved(InventoryReserved event) {
        inventoryReservedKafkaTemplate.send(TOPIC, event.getOrderId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish inventory.reserved for orderId={}", event.getOrderId(), ex);
                    } else {
                        log.info("Published inventory.reserved: orderId={}, success={}",
                                event.getOrderId(), event.getSuccess());
                    }
                });
    }
}
