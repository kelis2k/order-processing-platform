package ru.potekhincode.user.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;
import ru.potekhincode.user.model.OutboxEvent;
import ru.potekhincode.avro.UserRoleChanged;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class OutboxAvroMapper {

    private final ObjectMapper objectMapper;

    public SpecificRecord toAvro(OutboxEvent event) {
        try {
            return switch (event.getEventType()) {
                case "UserRoleChanged" -> toUserRoleChanged(event);
                default -> throw new IllegalStateException(
                        "Unknown outbox eventType: " + event.getEventType());
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize outbox payload for event " + event.getId(), e);
        }
    }

    private UserRoleChanged toUserRoleChanged(OutboxEvent event) throws JsonProcessingException {
        UserRoleChangedPayload p = objectMapper.readValue(event.getPayload(), UserRoleChangedPayload.class);
        return UserRoleChanged.newBuilder()
                .setUserId(p.userId())
                .setRole(p.role())
                .setTimestamp(Instant.ofEpochMilli(p.timestamp()))
                .build();
    }
}
