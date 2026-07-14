package ru.potekhincode.notification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.OrderStatusChanged;
import ru.potekhincode.notification.service.NotificationService;


@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangedConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "order.status-changed", containerFactory = "kafkaListenerContainerFactory")
    public void handle(OrderStatusChanged event) {
        notificationService.onStatusChanged(
                event.getOrderId().toString(),
                event.getUserId().toString(),
                event.getStatus().toString(),
                event.getReason() == null ? null : event.getReason().toString());
    }
}
