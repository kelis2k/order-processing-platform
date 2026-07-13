package ru.potekhincode.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LocalBucketStore {

    private final Cache<String, LocalBucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public boolean tryConsume(String key, ResilientRateLimiter.Config config) {
        return buckets.get(key, k -> new LocalBucket(config.getBurstCapacity()))
                .tryConsume(config.getRefillRate(), config.getBurstCapacity(), config.getRequestedTokens());
    }

    static final class LocalBucket {

        private double tokens;
        private long lastRefillNanos;

        LocalBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(int refillRate, int capacity, int requested) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillRate);
            if (tokens >= requested) {
                tokens -= requested;
                return true;
            }
            return false;
        }
    }
}
