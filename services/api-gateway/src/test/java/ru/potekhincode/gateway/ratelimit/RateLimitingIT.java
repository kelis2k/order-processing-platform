package ru.potekhincode.gateway.ratelimit;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * IT rate-limiting'а на границе (шаг 6.2): реальный Redis (Testcontainers) + реальные
 * фильтры Spring Cloud Gateway; бэкенды подменены {@link MockWebServer}, JWT — мок-декодером
 * (приём из 5.5: правила безопасности работают как в проде, подпись/JWKS не проверяются).
 * <p>
 * Лимиты выкручены в burst = 2 через env-override, чтобы не гонять десятки запросов.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitingIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    private static final MockWebServer BACKEND = new MockWebServer();

    static {
        REDIS.start();
        BACKEND.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{}");
            }
        });
        try {
            BACKEND.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start mock backend", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        String backendUrl = "http://" + BACKEND.getHostName() + ":" + BACKEND.getPort();
        registry.add("AUTH_URI", () -> backendUrl);
        registry.add("ORDER_URI", () -> backendUrl);

        // Ведро на 2 запроса (burst 10 / цена запроса 5), долив 1 токен/сек.
        // Дорогой запрос — защита от флака: Lua-скрипт считает время с точностью до секунды,
        // и при цене 1 случайно натёкший за время теста токен подарил бы лишний проход.
        registry.add("RL_AUTH_REFILL", () -> 1);
        registry.add("RL_AUTH_BURST", () -> 10);
        registry.add("RL_AUTH_TOKENS", () -> 5);
        registry.add("RL_ORDER_REFILL", () -> 1);
        registry.add("RL_ORDER_BURST", () -> 10);
        registry.add("RL_ORDER_TOKENS", () -> 5);
    }

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @BeforeEach
    void resetBuckets() {
        redis.keys("request_rate_limiter*")
                .flatMap(redis::delete)
                .blockLast(Duration.ofSeconds(5));
    }

    /**
     * Публичный путь: токена нет, ключ бакета — IP. Ведро на 2 запроса, третий отвергается.
     */
    @Test
    void anonymousBurstOnPublicPathIsLimitedByIp() {
        assertThat(loginStatus()).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus()).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Защищённый путь: ключ бакета — userId из токена, поэтому у двух пользователей
     * (с одного и того же IP) вёдра независимы — исчерпание лимита одним не задевает другого.
     */
    @Test
    void authenticatedUsersGetIndependentBuckets() {
        String alice = UUID.randomUUID().toString();
        String bob = UUID.randomUUID().toString();
        stubToken(alice);
        stubToken(bob);

        assertThat(ordersStatus(alice)).isEqualTo(HttpStatus.OK);
        assertThat(ordersStatus(alice)).isEqualTo(HttpStatus.OK);
        assertThat(ordersStatus(alice)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(ordersStatus(bob)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bucketKeyIsScopedByUserId() {
        String userId = UUID.randomUUID().toString();
        stubToken(userId);

        assertThat(ordersStatus(userId)).isEqualTo(HttpStatus.OK);

        List<String> keys = redis.keys("request_rate_limiter*").collectList().block(Duration.ofSeconds(5));
        assertThat(keys).isNotNull();
        assertThat(keys).anyMatch(key -> key.contains("user:" + userId));
        assertThat(keys).noneMatch(key -> key.contains("ip:"));
    }

    /**
     * Отвергнутый запрос до бэкенда не доходит — лимитер защищает сервисы, а не только себя.
     */
    @Test
    void rejectedRequestDoesNotReachBackend() {
        int before = BACKEND.getRequestCount();

        loginStatus();
        loginStatus();
        assertThat(loginStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(BACKEND.getRequestCount() - before).isEqualTo(2);
    }

    private HttpStatus loginStatus() {
        return HttpStatus.valueOf(webClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"x@example.com\",\"password\":\"nope\"}")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value());
    }

    private HttpStatus ordersStatus(String userId) {
        return HttpStatus.valueOf(webClient.get()
                .uri("/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value());
    }

    private void stubToken(String userId) {
        Jwt jwt = Jwt.withTokenValue(token(userId))
                .header("alg", "RS256")
                .issuer("auth-service")
                .subject(userId)
                .claim("role", "ROLE_USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(token(userId))).thenReturn(Mono.just(jwt));
    }

    private String token(String userId) {
        return "test-token-" + userId;
    }
}
