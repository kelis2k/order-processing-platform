package ru.potekhincode.notification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.UserCreated;
import ru.potekhincode.notification.service.RecipientService;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedConsumer {

    private final RecipientService recipientService;

    @KafkaListener(topics = "user.created", containerFactory = "kafkaListenerContainerFactory")
    public void handle(UserCreated event) {
        recipientService.upsert(
                event.getUserId().toString(),
                event.getEmail().toString(),
                event.getUsername().toString());
    }
}
