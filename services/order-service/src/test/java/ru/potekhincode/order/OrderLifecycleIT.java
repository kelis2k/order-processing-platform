package ru.potekhincode.order;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.potekhincode.avro.InventoryReserved;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.avro.OrderStatusChanged;
import ru.potekhincode.avro.ReservedItem;
import ru.potekhincode.order.dto.request.CreateOrderRequest;
import ru.potekhincode.order.dto.request.OrderItemRequest;
import ru.potekhincode.order.dto.response.OrderResponse;
import ru.potekhincode.order.model.OrderStatus;
import ru.potekhincode.order.model.SagaStatus;
import ru.potekhincode.order.repository.OrderRepository;
import ru.potekhincode.order.repository.OutboxRepository;
import ru.potekhincode.order.repository.SagaStateRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Сквозной (end-to-end) тест жизненного цикла заказа: REST → Outbox → Kafka → SAGA-реакция.
 * Поднимается полный Spring-контекст + Testcontainers Postgres/Kafka (см. {@link AbstractIntegrationTest}).
 * <p>
 * Тест играет роль inventory-service: после создания заказа он сам публикует
 * {@code inventory.reserved} и проверяет, что order-service довёл сагу до терминального состояния.
 * <ul>
 *   <li>happy path: {@code success=true}  → заказ RESERVED, сага RESERVED;</li>
 *   <li>compensation: {@code success=false} → заказ CANCELLED, сага CANCELLED, плюс
 *       компенсационное {@code order.status-changed} опубликовано через Outbox.</li>
 * </ul>
 */
class OrderLifecycleIT extends AbstractIntegrationTest {

    private static final String ORDER_CREATED_TOPIC = "order.created";
    private static final String INVENTORY_RESERVED_TOPIC = "inventory.reserved";
    private static final String ORDER_STATUS_CHANGED_TOPIC = "order.status-changed";

    private static final String USER_ID = "user-it-1";
    private static final String PRODUCT_ID = "prod-it-0000000000000001";
    private static final int QUANTITY = 3;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SagaStateRepository sagaStateRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    private KafkaConsumer<String, SpecificRecord> orderCreatedConsumer;
    private KafkaConsumer<String, SpecificRecord> statusChangedConsumer;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        sagaStateRepository.deleteAll();
        orderRepository.deleteAll();

        orderCreatedConsumer = newConsumer(ORDER_CREATED_TOPIC);
        statusChangedConsumer = newConsumer(ORDER_STATUS_CHANGED_TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (orderCreatedConsumer != null) {
            orderCreatedConsumer.close();
        }
        if (statusChangedConsumer != null) {
            statusChangedConsumer.close();
        }
    }

    @Test
    void happyPath_orderBecomesReserved() {
        UUID orderId = createOrder();

        // Outbox-поллер опубликовал order.created
        OrderCreated created = awaitRecord(orderCreatedConsumer, orderId, OrderCreated.class);
        assertThat(created.getUserId()).hasToString(USER_ID);
        assertThat(created.getItems()).hasSize(1);
        assertThat(created.getItems().get(0).getProductId()).hasToString(PRODUCT_ID);
        assertThat(created.getItems().get(0).getQuantity()).isEqualTo(QUANTITY);

        // inventory-service зарезервировал успешно
        publishInventoryReserved(orderId, true, null);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(orderRepository.findById(orderId)).get()
                    .extracting(o -> o.getStatus()).isEqualTo(OrderStatus.RESERVED);
            assertThat(sagaStateRepository.findById(orderId)).get()
                    .extracting(s -> s.getState()).isEqualTo(SagaStatus.RESERVED);
        });
    }

    @Test
    void compensationPath_orderBecomesCancelled() {
        UUID orderId = createOrder();
        awaitRecord(orderCreatedConsumer, orderId, OrderCreated.class);

        // inventory-service не смог зарезервировать → компенсация
        String reason = "недостаточно товара";
        publishInventoryReserved(orderId, false, reason);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(orderRepository.findById(orderId)).get()
                    .extracting(o -> o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(sagaStateRepository.findById(orderId)).get()
                    .extracting(s -> s.getState()).isEqualTo(SagaStatus.CANCELLED);
        });

        // компенсационное событие ушло в order.status-changed через Outbox
        OrderStatusChanged statusChanged = awaitRecord(statusChangedConsumer, orderId, OrderStatusChanged.class);
        assertThat(statusChanged.getStatus()).hasToString(OrderStatus.CANCELLED.name());
        assertThat(statusChanged.getReason()).hasToString(reason);
    }

    @Test
    void createOrderWithoutTokenIsUnauthorized() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(PRODUCT_ID, QUANTITY)));

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/orders", request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private UUID createOrder() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(PRODUCT_ID, QUANTITY)));
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/orders", HttpMethod.POST,
                new HttpEntity<>(request, bearer(USER_ID, "ROLE_USER")),
                OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(OrderStatus.NEW);
        return response.getBody().id();
    }

    private void publishInventoryReserved(UUID orderId, boolean success, String reason) {
        InventoryReserved event = InventoryReserved.newBuilder()
                .setOrderId(orderId.toString())
                .setItems(List.of(ReservedItem.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setQuantity(QUANTITY)
                        .build()))
                .setSuccess(success)
                .setReason(reason)
                .setTimestamp(Instant.now())
                .build();
        try (Producer<String, SpecificRecord> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(INVENTORY_RESERVED_TOPIC, orderId.toString(), event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish inventory.reserved", e);
        }
    }

    private <T extends SpecificRecord> T awaitRecord(
            KafkaConsumer<String, SpecificRecord> consumer, UUID orderId, Class<T> type) {
        String key = orderId.toString();
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, SpecificRecord> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, SpecificRecord> record : records) {
                if (key.equals(record.key()) && type.isInstance(record.value())) {
                    return type.cast(record.value());
                }
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " received for orderId=" + orderId);
    }

    private KafkaConsumer<String, SpecificRecord> newConsumer(String topic) {
        KafkaConsumer<String, SpecificRecord> consumer = new KafkaConsumer<>(consumerProps());
        consumer.subscribe(List.of(topic));
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
