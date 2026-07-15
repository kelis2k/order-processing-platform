package ru.potekhincode.notification.rateLimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.potekhincode.notification.AbstractIntegrationTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leaky-bucket лимитер против НАСТОЯЩЕГО Redis (Testcontainers) через Lua-скрипт из прода.
 * Ёмкость ведра — {@link #RL_CAPACITY} (задана в {@link AbstractIntegrationTest}).
 */
class EmailRateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private EmailRateLimiter rateLimiter;

    @Test
    void shouldAllowUpToCapacityThenDeny() {
        String userId = UUID.randomUUID().toString();

        for (int i = 0; i < RL_CAPACITY; i++) {
            assertThat(rateLimiter.tryAcquire(userId))
                    .as("письмо %d в пределах ёмкости", i + 1)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquire(userId))
                .as("письмо сверх ёмкости — подавлено")
                .isFalse();
    }

    /** Ведро одного пользователя не влияет на другого — ключ per-userId. */
    @Test
    void shouldKeepBucketsIndependentPerUser() {
        String heavy = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();

        for (int i = 0; i < RL_CAPACITY; i++) {
            rateLimiter.tryAcquire(heavy);
        }
        assertThat(rateLimiter.tryAcquire(heavy)).as("исчерпал квоту").isFalse();
        assertThat(rateLimiter.tryAcquire(fresh)).as("другой юзер — своё ведро").isTrue();
    }
}
