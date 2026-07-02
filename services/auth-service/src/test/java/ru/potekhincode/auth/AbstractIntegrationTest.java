package ru.potekhincode.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * База для интеграционных тестов auth-service: singleton-контейнеры PostgreSQL + Kafka
 * (стартуют один раз на JVM и переиспользуются наследниками, контекст Spring кэшируется).
 * <p>
 * Schema Registry не поднимается контейнером — используется confluent {@code MockSchemaRegistry}
 * через URL {@code mock://auth-it}. Один и тот же scope означает общий реестр схем для
 * продюсера приложения (Outbox → user.created) и тестовых Kafka-клиентов.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String SCHEMA_REGISTRY_URL = "mock://auth-it";

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                    .withDatabaseName("auth_db");

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> SCHEMA_REGISTRY_URL);
        registry.add("app.kafka.topic.replicas", () -> "1");
        registry.add("app.outbox.poll-interval-ms", () -> "200");
    }
}
