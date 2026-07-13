package ru.potekhincode.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты локального token bucket — резервного хранилища вёдер на время недоступности Redis.
 */
class LocalBucketStoreTest {

    private final LocalBucketStore store = new LocalBucketStore();

    private static ResilientRateLimiter.Config config(int refillRate, int burstCapacity) {
        return new ResilientRateLimiter.Config()
                .setRefillRate(refillRate)
                .setBurstCapacity(burstCapacity)
                .setRequestedTokens(1);
    }

    @Test
    void newBucketStartsFullAndIsExhaustedByBurst() {
        ResilientRateLimiter.Config config = config(1, 2);

        assertThat(store.tryConsume("ip:10.0.0.1", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.1", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.1", config)).isFalse();
    }

    /**
     * Долив «ленивый»: токены не начисляются таймером, а досчитываются по времени,
     * прошедшему с прошлого обращения. При 20 токенах/сек за 200 мс натекает ~4.
     */
    @Test
    void tokensAreRefilledOverTime() throws InterruptedException {
        ResilientRateLimiter.Config config = config(20, 2);

        assertThat(store.tryConsume("ip:10.0.0.2", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.2", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.2", config)).isFalse();

        Thread.sleep(200);

        assertThat(store.tryConsume("ip:10.0.0.2", config)).isTrue();
    }

    @Test
    void refillIsCappedByBurstCapacity() throws InterruptedException {
        ResilientRateLimiter.Config config = config(100, 2);

        assertThat(store.tryConsume("ip:10.0.0.3", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.3", config)).isTrue();

        Thread.sleep(200);

        // за 200 мс «натекло» ~20 токенов, но ведро вмещает только 2
        assertThat(store.tryConsume("ip:10.0.0.3", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.3", config)).isTrue();
        assertThat(store.tryConsume("ip:10.0.0.3", config)).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKey() {
        ResilientRateLimiter.Config config = config(1, 1);

        assertThat(store.tryConsume("user:alice", config)).isTrue();
        assertThat(store.tryConsume("user:alice", config)).isFalse();

        assertThat(store.tryConsume("user:bob", config)).isTrue();
    }
}
