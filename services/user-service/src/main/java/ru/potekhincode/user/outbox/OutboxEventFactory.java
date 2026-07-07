package ru.potekhincode.user.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.potekhincode.user.model.OutboxEvent;
import ru.potekhincode.user.model.UserProfile;

@Component
@RequiredArgsConstructor
public class OutboxEventFactory {
    private static final String AGGREGATE_TYPE = "USER";

    private final ObjectMapper objectMapper;

    public OutboxEvent userRoleChanged(UserProfile profile) {
        var payload = new UserRoleChangedPayload(
                profile.getId().toString(),
                profile.getRole().name(),
                System.currentTimeMillis()
        );
        return build("UserRoleChanged", "user.role-changed", profile.getId().toString(), payload);
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
