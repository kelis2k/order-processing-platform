package ru.potekhincode.notification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.notification.service.NotificationService;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "order.created", containerFactory = "kafkaListenerContainerFactory")
    public void handle(OrderCreated event) {
        notificationService.onOrderCreated(
                event.getOrderId().toString(),
                event.getUserId().toString());
    }
}
