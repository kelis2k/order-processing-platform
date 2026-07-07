package ru.potekhincode.auth.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.potekhincode.auth.model.Role;
import ru.potekhincode.auth.service.AuthService;
import ru.potekhincode.avro.UserRoleChanged;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserRoleChangedConsumer {

    private final AuthService authService;

    @KafkaListener(topics = "user.role-changed", containerFactory = "kafkaListenerContainerFactory")
    public void handle(UserRoleChanged event) {
        UUID userId = UUID.fromString(event.getUserId().toString());
        Role role = Role.valueOf(event.getRole().toString());
        log.info("Received user.role-changed: userId={}, role={}", userId, role);
        authService.applyRoleChange(userId, role);
    }
}
