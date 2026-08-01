package ru.potekhincode.inventory.kafka;

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
import ru.potekhincode.avro.InventoryReserved;
import ru.potekhincode.avro.OrderCreated;
import ru.potekhincode.avro.OrderStatusChanged;
import ru.potekhincode.avro.OrderLineItem;
import ru.potekhincode.inventory.AbstractIntegrationTest;
import ru.potekhincode.inventory.model.Inventory;
import ru.potekhincode.inventory.repository.InventoryRepository;
import ru.potekhincode.inventory.repository.ReservationRepository;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class InventoryKafkaIT extends AbstractIntegrationTest {

    private static final String ORDER_CREATED_TOPIC = "order.created";
    private static final String INVENTORY_RESERVED_TOPIC = "inventory.reserved";

    private static final String SUCCESS_PRODUCT_ID = "prod-it-success-00000001";
    private static final String COMP_PRODUCT_ID = "prod-it-comp-000000001";
    private static final String DUP_PRODUCT_ID = "prod-it-dup-0000000001";
    private static final String SHIP_PRODUCT_ID = "prod-it-ship-000000001";

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private KafkaConsumer<String, InventoryReserved> reservedConsumer;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        reservedConsumer = new KafkaConsumer<>(consumerProps());
        reservedConsumer.subscribe(List.of(INVENTORY_RESERVED_TOPIC));
    }

    @AfterEach
    void tearDown() {
        if (reservedConsumer != null) {
            reservedConsumer.close();
        }
    }

    @Test
    void reservesStockAndPublishesSuccessEvent() {
        inventoryRepository.save(inventory(SUCCESS_PRODUCT_ID, 10));
        String orderId = "order-" + UUID.randomUUID();

        publishOrderCreated(orderId, SUCCESS_PRODUCT_ID, 3);

        InventoryReserved event = awaitInventoryReserved(orderId);
        assertThat(event.getSuccess()).isTrue();
        assertThat(event.getItems()).hasSize(1);
        assertThat(event.getItems().get(0).getProductId()).hasToString(SUCCESS_PRODUCT_ID);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Inventory updated = inventoryRepository.findByProductId(SUCCESS_PRODUCT_ID).orElseThrow();
            assertThat(updated.getAvailable()).isEqualTo(7);
            assertThat(updated.getReserved()).isEqualTo(3);
        });
    }

    @Test
    void publishesFailureEventWhenStockInsufficient() {
        inventoryRepository.save(inventory(COMP_PRODUCT_ID, 2));
        String orderId = "order-" + UUID.randomUUID();

        publishOrderCreated(orderId, COMP_PRODUCT_ID, 999);

        InventoryReserved event = awaitInventoryReserved(orderId);
        assertThat(event.getSuccess()).isFalse();
        assertThat(event.getReason()).isNotNull();

        Inventory unchanged = inventoryRepository.findByProductId(COMP_PRODUCT_ID).orElseThrow();
        assertThat(unchanged.getAvailable()).isEqualTo(2);
        assertThat(unchanged.getReserved()).isZero();
    }

    @Test
    void duplicateOrderCreatedReservesStockOnlyOnce() {
        inventoryRepository.save(inventory(DUP_PRODUCT_ID, 10));
        String orderId = "order-" + UUID.randomUUID();

        publishOrderCreated(orderId, DUP_PRODUCT_ID, 3);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Inventory updated = inventoryRepository.findByProductId(DUP_PRODUCT_ID).orElseThrow();
            assertThat(updated.getAvailable()).isEqualTo(7);
            assertThat(updated.getReserved()).isEqualTo(3);
        });

        publishOrderCreated(orderId, DUP_PRODUCT_ID, 3);

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Inventory afterDuplicate = inventoryRepository.findByProductId(DUP_PRODUCT_ID).orElseThrow();
            assertThat(afterDuplicate.getAvailable()).isEqualTo(7);
            assertThat(afterDuplicate.getReserved()).isEqualTo(3);
        });

        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void shippedOrderCommitsReservationAndClearsReserved() {
        inventoryRepository.save(inventory(SHIP_PRODUCT_ID, 10));
        String orderId = "order-" + UUID.randomUUID();

        publishOrderCreated(orderId, SHIP_PRODUCT_ID, 4);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Inventory reserved = inventoryRepository.findByProductId(SHIP_PRODUCT_ID).orElseThrow();
            assertThat(reserved.getAvailable()).isEqualTo(6);
            assertThat(reserved.getReserved()).isEqualTo(4);
        });

        publishOrderStatusChanged(orderId, "SHIPPED");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Inventory shipped = inventoryRepository.findByProductId(SHIP_PRODUCT_ID).orElseThrow();
            assertThat(shipped.getAvailable()).isEqualTo(6);
            assertThat(shipped.getReserved()).isZero();
        });

        publishOrderStatusChanged(orderId, "SHIPPED");

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Inventory afterDuplicate = inventoryRepository.findByProductId(SHIP_PRODUCT_ID).orElseThrow();
            assertThat(afterDuplicate.getAvailable()).isEqualTo(6);
            assertThat(afterDuplicate.getReserved()).isZero();
        });
    }

    private Inventory inventory(String productId, int available) {
        return Inventory.builder()
                .productId(productId)
                .available(available)
                .reserved(0)
                .version(0)
                .build();
    }

    private void publishOrderStatusChanged(String orderId, String status) {
        OrderStatusChanged event = OrderStatusChanged.newBuilder()
                .setOrderId(orderId)
                .setUserId("user-1")
                .setStatus(status)
                .setReason(null)
                .setTimestamp(Instant.now())
                .build();
        try (Producer<String, OrderStatusChanged> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>("order.status-changed", orderId, event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish order.status-changed", e);
        }
    }

    private void publishOrderCreated(String orderId, String productId, int quantity) {
        OrderCreated event = OrderCreated.newBuilder()
                .setOrderId(orderId)
                .setUserId("user-1")
                .setItems(List.of(
                        OrderLineItem.newBuilder()
                                .setProductId(productId)
                                .setQuantity(quantity)
                                .build()))
                .setTimestamp(Instant.now())
                .build();
        try (Producer<String, OrderCreated> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(ORDER_CREATED_TOPIC, orderId, event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish order.created", e);
        }
    }

    private InventoryReserved awaitInventoryReserved(String orderId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, InventoryReserved> records = reservedConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, InventoryReserved> record : records) {
                if (orderId.equals(record.value().getOrderId().toString())) {
                    return record.value();
                }
            }
        }
        throw new AssertionError("No inventory.reserved received for orderId=" + orderId);
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
