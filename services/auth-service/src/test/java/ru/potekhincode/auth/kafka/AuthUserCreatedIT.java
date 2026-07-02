package ru.potekhincode.auth.kafka;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.potekhincode.auth.AbstractIntegrationTest;
import ru.potekhincode.auth.dto.RegisterRequest;
import ru.potekhincode.avro.UserCreated;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT продюсерской половины ТЗ §7: {@code POST /auth/register} → Outbox → OutboxPoller публикует
 * Avro-событие {@code user.created} в Kafka. Тест сам вычитывает топик и проверяет payload.
 */
class AuthUserCreatedIT extends AbstractIntegrationTest {

    private static final String USER_CREATED_TOPIC = "user.created";

    @Autowired
    private TestRestTemplate rest;

    private KafkaConsumer<String, UserCreated> consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaConsumer<>(consumerProps());
        consumer.subscribe(List.of(USER_CREATED_TOPIC));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void registerShouldPublishUserCreatedEvent() {
        String email = "kafka-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<Void> resp = rest.postForEntity(
                "/auth/register", new RegisterRequest(email, "kafkauser", "password1"), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UserCreated event = awaitUserCreated(email);

        assertThat(event.getEmail()).hasToString(email);
        assertThat(event.getUsername()).hasToString("kafkauser");
        assertThat(event.getUserId()).isNotNull();
        assertThat(event.getTimestamp()).isNotNull();
    }

    private UserCreated awaitUserCreated(String email) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, UserCreated> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, UserCreated> record : records) {
                if (email.equals(record.value().getEmail().toString())) {
                    // Ключ Kafka = userId (ТЗ §7): совпадает с полем userId в payload.
                    assertThat(record.key()).isEqualTo(record.value().getUserId().toString());
                    return record.value();
                }
            }
        }
        throw new AssertionError("No user.created received for email=" + email);
    }

    private Map<String, Object> consumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return props;
    }
}
