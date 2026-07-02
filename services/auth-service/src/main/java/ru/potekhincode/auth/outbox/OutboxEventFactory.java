package ru.potekhincode.auth.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.potekhincode.auth.model.OutboxEvent;
import ru.potekhincode.auth.model.User;

@Component
@RequiredArgsConstructor
public class OutboxEventFactory {
    private static final String AGGREGATE_TYPE = "USER";

    private final ObjectMapper objectMapper;

    public OutboxEvent userCreated(User user) {
        var payload = new UserCreatedPayload(
                user.getId().toString(),
                user.getEmail(),
                user.getUsername(),
                System.currentTimeMillis()
        );

        return build("UserCreated", "user.created", user.getId().toString(), payload);
    }

    private OutboxEvent build(String eventType,
                              String topic,
                              String aggregateId,
                              Object payload) {

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setTopic(topic);
        event.setMsgKey(aggregateId);

        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + eventType + " payload", e);
        }

        return event;
    }
}
