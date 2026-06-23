package ru.potekhincode.order.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.avro.OrderLineItem;
import ru.potekhincode.order.model.OutboxEvent;
import ru.potekhincode.order.repository.OutboxRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, OrderCreated> orderCreatedKafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            publish(event);
            event.setPublishedAt(OffsetDateTime.now()); // dirty-checking запишет при commit
        }
    }

    private void publish(OutboxEvent event) {
        try {
            OrderCreated payload = toOrderCreated(event);
            // синхронно: ждём ack брокера ДО простановки published_at
            orderCreatedKafkaTemplate.send(event.getTopic(), event.getMsgKey(), payload).get();
            log.info("Published {} from outbox: aggregateId={}", event.getEventType(), event.getAggregateId());
        } catch (Exception e) {
            // откатит транзакцию → published_at не запишется → повтор на след. тике
            throw new IllegalStateException("Failed to publish outbox event " + event.getId(), e);
        }
    }

    private OrderCreated toOrderCreated(OutboxEvent event) throws Exception {
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
}
