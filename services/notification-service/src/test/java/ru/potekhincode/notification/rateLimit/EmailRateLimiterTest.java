package ru.potekhincode.notification.rateLimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailRateLimiterTest {

    private static final String USER = "user-1";

    @Mock
    private StringRedisTemplate redis;

    private final RedisScript<Long> script = RedisScript.of("return 1", Long.class);

    private EmailRateLimiter limiter(boolean enabled) {
        return new EmailRateLimiter(redis, script, enabled, 5, 60000);
    }

    @Test
    void shouldAllowWhenScriptReturnsOne() {
        when(redis.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(1L);

        assertThat(limiter(true).tryAcquire(USER)).isTrue();
    }

    @Test
    void shouldDenyWhenScriptReturnsZero() {
        when(redis.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(0L);

        assertThat(limiter(true).tryAcquire(USER)).isFalse();
    }

    /** Redis лёг → fail-open: письмо важнее анти-спам-лимита (ADR 0010, зеркально 6.2). */
    @Test
    void shouldFailOpenWhenRedisUnavailable() {
        when(redis.execute(any(), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(limiter(true).tryAcquire(USER)).isTrue();
    }

    /** Выключенный лимитер вообще не ходит в Redis. */
    @Test
    void shouldSkipRedisWhenDisabled() {
        assertThat(limiter(false).tryAcquire(USER)).isTrue();

        verify(redis, never()).execute(any(), anyList(), (Object[]) any());
    }

    /** Наш скрипт всегда отдаёт 0/1; null — аномалия, трактуем строго как «не пропускать». */
    @Test
    void shouldDenyWhenScriptReturnsNull() {
        when(redis.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(null);

        assertThat(limiter(true).tryAcquire(USER)).isFalse();
    }
}
