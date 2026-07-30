package ru.potekhincode.order;

import org.junit.jupiter.api.BeforeEach;
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
import ru.potekhincode.order.client.InventoryClient;
import ru.potekhincode.order.client.ProductCatalogClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * База для интеграционных тестов order-service: общие контейнеры PostgreSQL + Kafka
 * (singleton-паттерн — стартуют один раз на JVM и переиспользуются всеми наследниками).
 * <p>
 * Schema Registry не поднимается контейнером — используется confluent {@code MockSchemaRegistry}
 * через URL {@code mock://order-it}. Один и тот же scope в URL означает общий реестр схем для
 * приложения и тестовых Kafka-клиентов.
 * <p>
 * gRPC-сервер отключён ({@code grpc.server.port=-1}) — стриминг покрыт отдельным
 * {@code OrderStatusGrpcServiceIT}. Синхронная gRPC-проверка наличия в {@code create()}
 * подменяется {@link MockitoBean}-заглушкой {@link InventoryClient} (всё в наличии), а запрос цен —
 * заглушкой {@link ProductCatalogClient}, поэтому реальные inventory- и product-service
 * в этих тестах не нужны.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String SCHEMA_REGISTRY_URL = "mock://order-it";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
                    .withDatabaseName("order_db");

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    /** Заглушка синхронной проверки наличия: всё доступно → заказ всегда создаётся. */
    @MockitoBean
    protected InventoryClient inventoryClient;

    /** Заглушка каталога цен: любой товар существует и стоит {@code 10.00}. */
    @MockitoBean
    protected ProductCatalogClient productCatalogClient;

    /**
     * Мок декодера JWT: resource-server работает как в проде (фильтр + правила ролей),
     * но подпись/JWKS не проверяются — {@link #bearer} стабит decode нужного токена.
     */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    /**
     * Готовит заголовки с валидным (с точки зрения resource-server) Bearer-токеном:
     * стабит {@code jwtDecoder.decode} на Jwt с заданными subject и ролью (claim {@code role}).
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
        registry.add("app.outbox.poll-interval-ms", () -> "500");

        registry.add("grpc.server.port", () -> "-1");
    }

    @BeforeEach
    void stubInventoryAvailable() {
        when(inventoryClient.checkAvailability(any())).thenReturn(List.of());
        when(productCatalogClient.getPrices(any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(), id -> new BigDecimal("10.00")));
        });
    }
}
