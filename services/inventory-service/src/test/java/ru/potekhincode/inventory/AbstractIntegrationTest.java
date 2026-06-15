package ru.potekhincode.inventory;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * База для интеграционных тестов: общие контейнеры PostgreSQL + Kafka (singleton-паттерн —
 * стартуют один раз на JVM и переиспользуются всеми наследниками).
 * <p>
 * Schema Registry не поднимается контейнером — используется confluent
 * {@code MockSchemaRegistry} через URL {@code mock://...}. Один и тот же scope в URL
 * означает общий реестр схем для producer и consumer (и приложения, и тестовых клиентов).
 * gRPC-сервер переключён в in-process режим (netty-порт отключён через -1).
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final String SCHEMA_REGISTRY_URL = "mock://inventory-it";
    protected static final String GRPC_IN_PROCESS_NAME = "inventory-it";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                    .withDatabaseName("inventory_db");

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

        registry.add("grpc.server.port", () -> "-1");
        registry.add("grpc.server.in-process-name", () -> GRPC_IN_PROCESS_NAME);
    }
}
