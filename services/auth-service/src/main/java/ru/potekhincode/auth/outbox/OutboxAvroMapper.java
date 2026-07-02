package ru.potekhincode.auth.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;
import ru.potekhincode.auth.model.OutboxEvent;
import ru.potekhincode.avro.UserCreated;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class OutboxAvroMapper {

    private final ObjectMapper objectMapper;

    public SpecificRecord toAvro(OutboxEvent event) {
        try {
            return switch (event.getEventType()) {
                case "UserCreated" -> toUserCreated(event);
                default -> throw new IllegalStateException(
                        "Unknown outbox eventType: " + event.getEventType());
                };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize outbox payload for event " + event.getId(), e);
        }
    }

    private UserCreated toUserCreated(OutboxEvent event) throws JsonProcessingException {
        UserCreatedPayload p = objectMapper.readValue(event.getPayload(), UserCreatedPayload.class);
        return UserCreated.newBuilder()
                .setUserId(p.userId())
                .setEmail(p.email())
                .setUsername(p.username())
                .setTimestamp(Instant.ofEpochMilli(p.timestamp()))
                .build();
    }
}
