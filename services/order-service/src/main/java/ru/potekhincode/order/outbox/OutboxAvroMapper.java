package ru.potekhincode.order.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.avro.OrderLineItem;
import ru.potekhincode.avro.OrderStatusChanged;
import ru.potekhincode.order.model.OutboxEvent;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OutboxAvroMapper {

    private final ObjectMapper objectMapper;

    public SpecificRecord toAvro(OutboxEvent event) {
        try {
            return switch (event.getEventType()) {
                case "OrderCreated"       -> toOrderCreated(event);
                case "OrderStatusChanged" -> toOrderStatusChanged(event);
                default -> throw new IllegalStateException(
                        "Unknown outbox eventType: " + event.getEventType());
            };
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize outbox payload for event " + event.getId(), e);
        }
    }

    private OrderCreated toOrderCreated(OutboxEvent event) throws com.fasterxml.jackson.core.JsonProcessingException {
        OrderCreatedPayload payload = objectMapper.readValue(event.getPayload(), OrderCreatedPayload.class);
        List<OrderLineItem> items = payload.items().stream()
                .map(i -> OrderLineItem.newBuilder()
                        .setProductId(i.productId())
                        .setQuantity(i.quantity())
                        .build())
                .toList();
        return OrderCreated.newBuilder()
                .setOrderId(payload.orderId())
                .setUserId(payload.userId())
                .setItems(items)
                .setTimestamp(Instant.ofEpochMilli(payload.timestamp()))
                .build();
    }

    private OrderStatusChanged toOrderStatusChanged(OutboxEvent event) throws com.fasterxml.jackson.core.JsonProcessingException {
        OrderStatusChangedPayload p = objectMapper.readValue(event.getPayload(), OrderStatusChangedPayload.class);
        return OrderStatusChanged.newBuilder()
                .setOrderId(p.orderId())
                .setStatus(p.status())
                .setReason(p.reason())
                .setTimestamp(Instant.ofEpochMilli(p.timestamp()))
                .build();
    }
}