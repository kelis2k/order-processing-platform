package ru.potekhincode.user.kafka;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.potekhincode.avro.UserCreated;
import ru.potekhincode.avro.UserRoleChanged;
import ru.potekhincode.user.AbstractIntegrationTest;
import ru.potekhincode.user.model.Role;
import ru.potekhincode.user.model.UserProfile;
import ru.potekhincode.user.repository.OutboxRepository;
import ru.potekhincode.user.repository.UserProfileRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * IT обеих Kafka-половин user-service:
 * <ul>
 *   <li>consumer: {@code user.created} → идемпотентное создание профиля (ROLE_USER);</li>
 *   <li>producer: {@code PUT /users/{id}/role} → Outbox → OutboxPoller публикует
 *       {@code user.role-changed} (мост ADR 0005 B2, key = userId).</li>
 * </ul>
 * Полный Spring-контекст + Testcontainers Postgres/Kafka, Schema Registry — {@code MockSchemaRegistry}.
 */
class UserProfileKafkaIT extends AbstractIntegrationTest {

    private static final String USER_CREATED_TOPIC = "user.created";
    private static final String USER_ROLE_CHANGED_TOPIC = "user.role-changed";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserProfileRepository profileRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        profileRepository.deleteAll();
    }

    @Test
    void userCreatedShouldCreateProfileWithRoleUser() {
        UUID userId = UUID.randomUUID();
        String email = "created-" + userId + "@example.com";

        publishUserCreated(userId, email, "created_user");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(profileRepository.findById(userId)).get()
                        .satisfies(p -> {
                            assertThat(p.getEmail()).isEqualTo(email);
                            assertThat(p.getUsername()).isEqualTo("created_user");
                            assertThat(p.getRole()).isEqualTo(Role.ROLE_USER);
                        }));
    }

    @Test
    void changeRoleShouldPublishUserRoleChanged() {
        UUID userId = UUID.randomUUID();
        seedProfile(userId, "role-" + userId + "@example.com", "role_user", Role.ROLE_USER);

        try (KafkaConsumer<String, UserRoleChanged> consumer = newRoleChangedConsumer()) {
            ResponseEntity<Void> resp = rest.exchange(
                    "/users/" + userId + "/role",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("role", "ROLE_MANAGER"), bearer("admin-it", "ROLE_ADMIN")),
                    Void.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

            UserRoleChanged event = awaitRoleChanged(consumer, userId);
            assertThat(event.getRole()).hasToString("ROLE_MANAGER");
        }

        // истина роли в user-service тоже обновлена
        assertThat(profileRepository.findById(userId)).get()
                .extracting(UserProfile::getRole).isEqualTo(Role.ROLE_MANAGER);
    }

    private void seedProfile(UUID id, String email, String username, Role role) {
        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setEmail(email);
        profile.setUsername(username);
        profile.setRole(role);
        profileRepository.save(profile);
    }

    private void publishUserCreated(UUID userId, String email, String username) {
        UserCreated event = UserCreated.newBuilder()
                .setUserId(userId.toString())
                .setEmail(email)
                .setUsername(username)
                .setTimestamp(Instant.now())
                .build();
        try (Producer<String, Object> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(USER_CREATED_TOPIC, userId.toString(), event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish user.created", e);
        }
    }

    private UserRoleChanged awaitRoleChanged(KafkaConsumer<String, UserRoleChanged> consumer, UUID userId) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, UserRoleChanged> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, UserRoleChanged> record : records) {
                if (userId.toString().equals(record.value().getUserId().toString())) {
                    assertThat(record.key()).isEqualTo(userId.toString());
                    return record.value();
                }
            }
        }
        throw new AssertionError("No user.role-changed received for userId=" + userId);
    }

    private KafkaConsumer<String, UserRoleChanged> newRoleChangedConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        KafkaConsumer<String, UserRoleChanged> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(USER_ROLE_CHANGED_TOPIC));
        return consumer;
    }

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        return props;
    }
}
