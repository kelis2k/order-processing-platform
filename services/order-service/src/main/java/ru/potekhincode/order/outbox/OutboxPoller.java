package ru.potekhincode.order.outbox;

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
        try {
            avroKafkaTemplate.send(event.getTopic(), event.getMsgKey(), avroMapper.toAvro(event)).get();
            log.info("Published {} from outbox: aggregateId={}", event.getEventType(), event.getAggregateId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox event " + event.getId(), e);
        }
    }
}
