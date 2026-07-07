package ru.potekhincode.user;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.mockito.Mockito.when;

/**
 * База для интеграционных тестов user-service: singleton-контейнеры PostgreSQL + Kafka
 * (стартуют один раз на JVM и переиспользуются наследниками, контекст Spring кэшируется).
 * <p>
 * Schema Registry не поднимается контейнером — используется confluent {@code MockSchemaRegistry}
 * через URL {@code mock://user-it}. Один и тот же scope означает общий реестр схем для
 * приложения (consumer user.created + Outbox → user.role-changed) и тестовых Kafka-клиентов.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String SCHEMA_REGISTRY_URL = "mock://user-it";

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                    .withDatabaseName("user_db");

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    /**
     * Мок декодера JWT: resource-server работает как в проде (фильтр + правила ролей),
     * но подпись/JWKS не проверяются — {@link #bearer} стабит decode нужного токена.
     */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    /**
     * Заголовки с валидным (для resource-server) Bearer-токеном: стабит
     * {@code jwtDecoder.decode} на Jwt с заданными subject и ролью (claim {@code role}).
     */
    protected HttpHeaders bearer(String subject, String role) {
        String token = "test-" + role + "-" + subject;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject)
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
