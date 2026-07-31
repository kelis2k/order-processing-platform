package ru.potekhincode.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.potekhincode.order.model.Order;
import ru.potekhincode.order.model.OutboxEvent;

import java.util.HashMap;
import java.util.Map;

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
                order.getUserId(),
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

        event.setTraceContext(currentTraceparent());   // сшивка трейса сквозь Outbox (Шаг 7.4, вар. B)
        return event;
    }

    private String currentTraceparent() {
        Map<String, String> carrier = new HashMap<>();
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), carrier, (c, k, v) -> {
                    if (c != null) {
                        c.put(k, v);
                    }
                });
        return carrier.get("traceparent");
    }
}
