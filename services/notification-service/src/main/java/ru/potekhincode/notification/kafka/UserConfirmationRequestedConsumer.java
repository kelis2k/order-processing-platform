package ru.potekhincode.notification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.UserConfirmationRequested;
import ru.potekhincode.notification.service.NotificationService;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserConfirmationRequestedConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "user.confirmation-requested", containerFactory = "kafkaListenerContainerFactory")
    public void handle(UserConfirmationRequested event) {
        notificationService.onConfirmationRequested(
                event.getUserId().toString(),
                event.getEmail().toString(),
                event.getUsername().toString(),
                event.getToken().toString(),
                event.getExpiresAt());
    }
}
