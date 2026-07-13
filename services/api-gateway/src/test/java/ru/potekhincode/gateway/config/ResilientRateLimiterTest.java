package ru.potekhincode.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Юнит-тесты {@link ResilientRateLimiter} без Redis: скрипт-вызов мокается.
 * <p>
 * Ключевой сценарий — деградация: коробочный {@code RedisRateLimiter} при ошибке Redis
 * молча пропускает все запросы (fail-open). Наш лимитер вместо этого переходит на
 * локальное ведро и инкрементит метрику {@code gateway.ratelimit.degraded}.
 */
class ResilientRateLimiterTest {

    private static final String ROUTE = "order-service";
    private static final String KEY = "user:8f1b6c2e-0000-4000-8000-000000000001";

    @SuppressWarnings("unchecked")
    private final RedisScript<List<Long>> script = mock(RedisScript.class);
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private ResilientRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ResilientRateLimiter(redis, script, null, new LocalBucketStore(), meters, 100);
        limiter.getConfig().put(ROUTE, new ResilientRateLimiter.Config()
                .setRefillRate(1)
                .setBurstCapacity(2)
                .setRequestedTokens(1));
    }

    @Test
    void allowsRequestWhenRedisGrantsToken() {
        stubScript(Flux.just(List.of(1L, 4L)));

        RateLimiter.Response response = limiter.isAllowed(ROUTE, KEY).block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders())
                .containsEntry("X-RateLimit-Remaining", "4")
                .containsEntry("X-RateLimit-Refill-Rate", "1")
                .containsEntry("X-RateLimit-Burst-Capacity", "2");
    }

    @Test
    void deniesRequestWhenRedisReportsEmptyBucket() {
        stubScript(Flux.just(List.of(0L, 0L)));

        RateLimiter.Response response = limiter.isAllowed(ROUTE, KEY).block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isFalse();
    }

    /**
     * Redis недоступен: решение принимает локальное ведро (burst = 2), лимит сохраняется,
     * деградация видна в метрике. Коробочный лимитер пропустил бы все три запроса.
     */
    @Test
    void fallsBackToLocalBucketWhenRedisIsDown() {
        stubScript(Flux.error(new RedisConnectionFailureException("redis is down")));

        assertThat(allowed()).isTrue();
        assertThat(allowed()).isTrue();
        assertThat(allowed()).isFalse();

        assertThat(meters.counter("gateway.ratelimit.degraded", "route", ROUTE).count()).isEqualTo(3.0);
    }

    @Test
    void degradedResponseReportsUnknownRemaining() {
        stubScript(Flux.error(new RedisConnectionFailureException("redis is down")));

        RateLimiter.Response response = limiter.isAllowed(ROUTE, KEY).block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.getHeaders()).containsEntry("X-RateLimit-Remaining", "-1");
    }

    /**
     * Маршрут без args лимитера — ошибка конфигурации, а не «пропустить всех»: падаем громко.
     */
    @Test
    void failsLoudlyWhenRouteHasNoConfig() {
        assertThatThrownBy(() -> limiter.isAllowed("unknown-route", KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-route");
    }

    private boolean allowed() {
        RateLimiter.Response response = limiter.isAllowed(ROUTE, KEY).block(Duration.ofSeconds(1));
        assertThat(response).isNotNull();
        return response.isAllowed();
    }

    private void stubScript(Flux<List<Long>> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), anyList());
    }
}
