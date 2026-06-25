package ru.potekhincode.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OutboxEvent;

@Component
@RequiredArgsConstructor
public class OutboxEventFactory {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final ObjectMapper objectMapper;

    public OutboxEvent orderCreated(Order order) {
        var items = order.getItems().stream()
                .map(i -> new OrderCreatedPayload.Item(i.getProductId(), i.getQuantity()))
                .toList();

        var payload = new OrderCreatedPayload(
                order.getId().toString(),
                order.getUserId(),
                items,
                System.currentTimeMillis()
        );

        return build("OrderCreated", "order.created", order.getId().toString(), payload);
    }

    public OutboxEvent orderStatusChanged(Order order, String reason) {
        var payload = new OrderStatusChangedPayload(
                order.getId().toString(),
                order.getStatus().name(),
                reason,
                System.currentTimeMillis());

        return build("OrderStatusChanged", "order.status-changed", order.getId().toString(), payload);
    }


    private OutboxEvent build(String eventType, String topic, String aggregateId, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setTopic(topic);
        event.setMsgKey(aggregateId);            // ключ Kafka = orderId

        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + eventType + " payload", e);
        }

        return event;
    }
}
