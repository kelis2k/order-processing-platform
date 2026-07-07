package ru.potekhincode.auth.kafka;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.potekhincode.auth.AbstractIntegrationTest;
import ru.potekhincode.auth.model.AuthProvider;
import ru.potekhincode.auth.model.Role;
import ru.potekhincode.auth.model.User;
import ru.potekhincode.auth.repository.UserRepository;
import ru.potekhincode.avro.UserRoleChanged;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * IT потребляющей половины моста ADR 0005 B2: user-service публикует {@code user.role-changed},
 * auth-service (реплика роли) слушает и перезаписывает {@code users.role}. Тест играет роль
 * user-service: сам публикует Avro-событие и проверяет, что реплика в auth_db обновилась.
 */
class UserRoleChangedIT extends AbstractIntegrationTest {

    private static final String USER_ROLE_CHANGED_TOPIC = "user.role-changed";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private UserRepository userRepository;

    @Test
    void userRoleChangedShouldUpdateReplicaRole() {
        User user = new User();
        user.setEmail("replica-" + UUID.randomUUID() + "@example.com");
        user.setUsername("replica_user");
        user.setPasswordHash("{noop}x");
        user.setRole(Role.ROLE_USER);
        user.setEnabled(true);
        user.setProvider(AuthProvider.LOCAL);
        UUID userId = userRepository.save(user).getId();

        publishUserRoleChanged(userId, "ROLE_MANAGER");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(userRepository.findById(userId)).get()
                        .extracting(User::getRole).isEqualTo(Role.ROLE_MANAGER));
    }

    private void publishUserRoleChanged(UUID userId, String role) {
        UserRoleChanged event = UserRoleChanged.newBuilder()
                .setUserId(userId.toString())
                .setRole(role)
                .setTimestamp(Instant.now())
                .build();
        try (Producer<String, Object> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(USER_ROLE_CHANGED_TOPIC, userId.toString(), event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish user.role-changed", e);
        }
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
