package ru.potekhincode.user.kafka;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.UserCreated;
import ru.potekhincode.user.service.UserProfileService;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedConsumer {
    private final UserProfileService userProfileService;

    @KafkaListener(topics = "user.created", containerFactory = "kafkaListenerContainerFactory")
    public void handle(UserCreated event) {
        UUID userId = UUID.fromString(event.getUserId().toString());

        log.info("Received user.created: userId={}, email={}", userId, event.getEmail());

        userProfileService.createFromEvent(
                userId,
                event.getEmail().toString(),
                event.getUsername().toString());
    }
}
