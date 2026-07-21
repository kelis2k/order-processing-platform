package ru.potekhincode.order.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.potekhincode.order.model.OutboxEvent;
import ru.potekhincode.order.repository.OutboxRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, SpecificRecord> avroKafkaTemplate;
    private final OutboxAvroMapper avroMapper;

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
        // Восстанавливаем контекст трейса исходного запроса: producer-span (и весь
        // Kafka-хвост SAGA) становится частью того же трейса, что и POST /orders (вар. B).
        try (Scope ignored = restoreContext(event).makeCurrent()) {
            avroKafkaTemplate.send(event.getTopic(), event.getMsgKey(), avroMapper.toAvro(event)).get();
            log.info("Published {} from outbox: aggregateId={}", event.getEventType(), event.getAggregateId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox event " + event.getId(), e);
        }
    }

    /** Собирает Context из сохранённого traceparent; без него/без агента — текущий контекст.
     *  Package-private ради юнит-теста round-trip (см. OutboxTracePropagationTest). */
    Context restoreContext(OutboxEvent event) {
        if (event.getTraceContext() == null) {
            return Context.current();
        }
        Map<String, String> carrier = Map.of("traceparent", event.getTraceContext());
        return GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), carrier, TRACE_GETTER);
    }

    private static final TextMapGetter<Map<String, String>> TRACE_GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
}
