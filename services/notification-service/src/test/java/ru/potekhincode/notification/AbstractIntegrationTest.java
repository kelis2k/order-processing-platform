package ru.potekhincode.notification;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
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

    /** SMTP-сервер в памяти (не контейнер) — реальная отправка писем в IT без MailHog. */
    protected static final GreenMail GREENMAIL = new GreenMail(ServerSetupTest.SMTP);

    /** Redis для анти-спам-лимитера (6.6): без него контекст не стартует — сервис зависит от него. */
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    /** Ёмкость ведра в IT — небольшая, чтобы EmailRateLimiterIT мог упереться в лимит. */
    protected static final int RL_CAPACITY = 3;

    static {
        MONGO.start();
        KAFKA.start();
        GREENMAIL.start();
        REDIS.start();
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
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> GREENMAIL.getSmtp().getPort());
        registry.add("app.mail.from", () -> "no-reply@notification-it");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.rate-limit.enabled", () -> "true");
        registry.add("app.rate-limit.capacity", () -> String.valueOf(RL_CAPACITY));
        registry.add("app.rate-limit.window-ms", () -> "60000");
        // ретраи в тестах короткие: неизвестный получатель не должен растягивать прогон
        registry.add("app.kafka.consumer.retry.initial-interval-ms", () -> "200");
        registry.add("app.kafka.consumer.retry.max-interval-ms", () -> "500");
        registry.add("app.kafka.consumer.retry.max-attempts", () -> "3");
    }
}
