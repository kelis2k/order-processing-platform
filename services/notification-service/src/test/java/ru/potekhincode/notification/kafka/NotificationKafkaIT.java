package ru.potekhincode.notification.kafka;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.avro.OrderLineItem;
import ru.potekhincode.avro.OrderStatusChanged;
import ru.potekhincode.avro.UserCreated;
import ru.potekhincode.notification.AbstractIntegrationTest;
import ru.potekhincode.notification.model.DeliveryState;
import ru.potekhincode.notification.model.Notification;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.repository.NotificationRepository;
import ru.potekhincode.notification.repository.RecipientRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Сквозной IT потребляющей стороны notification-service (шаг 6.4):
 * три топика → проекция получателей + идемпотентный журнал писем в MongoDB.
 * <p>
 * Тест играет роль остальных сервисов: сам публикует Avro-события, как это делают
 * auth-service ({@code user.created}) и order-service ({@code order.created},
 * {@code order.status-changed} — последний уже с полем {@code userId}, ADR 0009).
 */
class NotificationKafkaIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String EMAIL = "it-notify@example.com";

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private RecipientRepository recipientRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        recipientRepository.deleteAll();
    }

    @Test
    void userCreatedShouldBuildRecipientProjection() {
        String userId = UUID.randomUUID().toString();

        publish(USER_CREATED_TOPIC, userId, userCreated(userId, EMAIL));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(recipientRepository.findById(userId)).get()
                        .satisfies(r -> assertThat(r.getEmail()).isEqualTo(EMAIL)));
    }

    @Test
    void orderCreatedShouldRecordAcceptedNotification() {
        String userId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        publish(USER_CREATED_TOPIC, userId, userCreated(userId, EMAIL));
        awaitRecipient(userId);

        publish(ORDER_CREATED_TOPIC, orderId, orderCreated(orderId, userId));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Notification n = single(orderId);
            assertThat(n.getType()).isEqualTo(NotificationType.ORDER_ACCEPTED);
            assertThat(n.getStatus()).isEqualTo("NEW");
            assertThat(n.getRecipientEmail()).isEqualTo(EMAIL);
            assertThat(n.getState()).isEqualTo(DeliveryState.PENDING);
        });
    }

    /** Получатель берётся из userId В САМОМ событии (ADR 0009) — проекции orderId→userId нет. */
    @Test
    void statusChangedShouldRecordNotificationWithReason() {
        String userId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        String reason = "Insufficient stock for product p-1: requested 1, available 0";
        publish(USER_CREATED_TOPIC, userId, userCreated(userId, EMAIL));
        awaitRecipient(userId);

        publish(ORDER_STATUS_CHANGED_TOPIC, orderId, statusChanged(orderId, userId, "CANCELLED", reason));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Notification n = single(orderId);
            assertThat(n.getType()).isEqualTo(NotificationType.ORDER_STATUS_CHANGED);
            assertThat(n.getStatus()).isEqualTo("CANCELLED");
            assertThat(n.getReason()).isEqualTo(reason);
            assertThat(n.getRecipientEmail()).isEqualTo(EMAIL);
        });
    }

    /**
     * Ключевая проверка шага: at-least-once не должен превращаться в два письма.
     * Повторная доставка того же факта отбивается уникальным индексом
     * (type, aggregateId, status) → DuplicateKeyException → no-op.
     */
    @Test
    void redeliveredEventShouldNotProduceSecondNotification() {
        String userId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        publish(USER_CREATED_TOPIC, userId, userCreated(userId, EMAIL));
        awaitRecipient(userId);

        OrderStatusChanged event = statusChanged(orderId, userId, "RESERVED", null);
        publish(ORDER_STATUS_CHANGED_TOPIC, orderId, event);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(byOrder(orderId)).hasSize(1));

        publish(ORDER_STATUS_CHANGED_TOPIC, orderId, event);   // тот же факт ещё раз
        publish(ORDER_STATUS_CHANGED_TOPIC, orderId, event);

        // ждём заведомо дольше обработки и убеждаемся, что документ по-прежнему один
        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(byOrder(orderId)).hasSize(1));
    }

    /** Разные статусы одного заказа — разные факты: два письма, оба законны. */
    @Test
    void differentStatusesOfSameOrderShouldProduceSeparateNotifications() {
        String userId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        publish(USER_CREATED_TOPIC, userId, userCreated(userId, EMAIL));
        awaitRecipient(userId);

        publish(ORDER_CREATED_TOPIC, orderId, orderCreated(orderId, userId));
        publish(ORDER_STATUS_CHANGED_TOPIC, orderId, statusChanged(orderId, userId, "RESERVED", null));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(byOrder(orderId))
                        .extracting(Notification::getStatus)
                        .containsExactlyInAnyOrder("NEW", "RESERVED"));
    }

    private void awaitRecipient(String userId) {
        await().atMost(TIMEOUT).until(() -> recipientRepository.findById(userId).isPresent());
    }

    private List<Notification> byOrder(String orderId) {
        return notificationRepository.findAll().stream()
                .filter(n -> orderId.equals(n.getAggregateId()))
                .toList();
    }

    private Notification single(String orderId) {
        List<Notification> found = byOrder(orderId);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private UserCreated userCreated(String userId, String email) {
        return UserCreated.newBuilder()
                .setUserId(userId)
                .setEmail(email)
                .setUsername("it-user")
                .setTimestamp(Instant.now())
                .build();
    }

    private OrderCreated orderCreated(String orderId, String userId) {
        return OrderCreated.newBuilder()
                .setOrderId(orderId)
                .setUserId(userId)
                .setItems(List.of(OrderLineItem.newBuilder()
                        .setProductId("p-1")
                        .setQuantity(1)
                        .build()))
                .setTimestamp(Instant.now())
                .build();
    }

    private OrderStatusChanged statusChanged(String orderId, String userId, String status, String reason) {
        return OrderStatusChanged.newBuilder()
                .setOrderId(orderId)
                .setUserId(userId)
                .setStatus(status)
                .setReason(reason)
                .setTimestamp(Instant.now())
                .build();
    }

    private void publish(String topic, String key, SpecificRecord event) {
        try (Producer<String, Object> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, key, event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish to " + topic, e);
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
