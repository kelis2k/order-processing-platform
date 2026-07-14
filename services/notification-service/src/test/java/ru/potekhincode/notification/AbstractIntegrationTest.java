package ru.potekhincode.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * База для интеграционных тестов notification-service: singleton-контейнеры MongoDB + Kafka
 * (стартуют один раз на JVM, контекст Spring кэшируется между наследниками).
 * <p>
 * Schema Registry контейнером не поднимаем — confluent {@code MockSchemaRegistry} через URL
 * {@code mock://notification-it}: один scope на приложение и тестовые Kafka-клиенты.
 * <p>
 * Топики создаёт тестовая конфигурация: notification-service — только потребитель, в проде
 * топики заводят продюсеры (order/auth/user-service), но в IT их создать некому, а подписка
 * на несуществующий топик ждала бы обновления метаданных.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String SCHEMA_REGISTRY_URL = "mock://notification-it";

    protected static final String USER_CREATED_TOPIC = "user.created";
    protected static final String ORDER_CREATED_TOPIC = "order.created";
    protected static final String ORDER_STATUS_CHANGED_TOPIC = "order.status-changed";

    @SuppressWarnings("resource")
    protected static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        MONGO.start();
        KAFKA.start();
    }

    @TestConfiguration
    static class TopicsConfig {

        @Bean
        NewTopic userCreatedTopic() {
            return TopicBuilder.name(USER_CREATED_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic orderCreatedTopic() {
            return TopicBuilder.name(ORDER_CREATED_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic orderStatusChangedTopic() {
            return TopicBuilder.name(ORDER_STATUS_CHANGED_TOPIC).partitions(1).replicas(1).build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("notification_db"));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> SCHEMA_REGISTRY_URL);
        // ретраи в тестах короткие: неизвестный получатель не должен растягивать прогон
        registry.add("app.kafka.consumer.retry.initial-interval-ms", () -> "200");
        registry.add("app.kafka.consumer.retry.max-interval-ms", () -> "500");
        registry.add("app.kafka.consumer.retry.max-attempts", () -> "3");
    }
}
