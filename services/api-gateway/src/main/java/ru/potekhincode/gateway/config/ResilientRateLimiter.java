package ru.potekhincode.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Primary
@Component
public class ResilientRateLimiter extends AbstractRateLimiter<ResilientRateLimiter.Config> {

    public static final String CONFIGURATION_PROPERTY_NAME = "resilient-rate-limiter";

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List<Long>> script;
    private final LocalBucketStore localBuckets;
    private final MeterRegistry meters;
    private final Duration redisTimeout;

    public ResilientRateLimiter(ReactiveStringRedisTemplate redis,
                                @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
                                ConfigurationService configurationService,
                                LocalBucketStore localBuckets,
                                MeterRegistry meters,
                                @Value("${app.rate-limit.redis-timeout-ms:100}") long redisTimeoutMs) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.redis = redis;
        this.script = script;
        this.localBuckets = localBuckets;
        this.meters = meters;
        this.redisTimeout = Duration.ofMillis(redisTimeoutMs);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = loadConfig(routeId);

        List<String> keys = List.of(
                "request_rate_limiter.{" + id + "}.tokens",
                "request_rate_limiter.{" + id + "}.timestamp");
        List<String> args = List.of(
                String.valueOf(config.getRefillRate()),
                String.valueOf(config.getBurstCapacity()),
                String.valueOf(Instant.now().getEpochSecond()),
                String.valueOf(config.getRequestedTokens()));

        return redis.execute(script, keys, args)
                .reduce(new ArrayList<Long>(), (acc, chunk) -> {
                    acc.addAll(chunk);
                    return acc;
                })
                .timeout(redisTimeout)
                .map(result -> response(result.get(0) == 1L, result.get(1), config))
                .onErrorResume(error -> Mono.just(degraded(routeId, id, config, error)));
    }

    private Response degraded(String routeId, String id, Config config, Throwable error) {
        meters.counter("gateway.ratelimit.degraded", "route", routeId).increment();
        log.warn("Redis unavailable for rate limiting (route={}): {}. Falling back to local bucket",
                routeId, error.toString());
        boolean allowed = localBuckets.tryConsume(id, config);
        return response(allowed, -1L, config);
    }

    private Response response(boolean allowed, long tokensLeft, Config config) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-RateLimit-Remaining", String.valueOf(tokensLeft));
        headers.put("X-RateLimit-Refill-Rate", String.valueOf(config.getRefillRate()));
        headers.put("X-RateLimit-Burst-Capacity", String.valueOf(config.getBurstCapacity()));
        return new Response(allowed, headers);
    }

    private Config loadConfig(String routeId) {
        Config config = getConfig().get(routeId);
        if (config == null) {
            throw new IllegalArgumentException("No rate limiter config for route " + routeId);
        }
        return config;
    }

    public static class Config {

        private int refillRate;
        private int burstCapacity = 1;
        private int requestedTokens = 1;

        public int getRefillRate() {
            return refillRate;
        }

        public Config setRefillRate(int refillRate) {
            this.refillRate = refillRate;
            return this;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public Config setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
            return this;
        }
    }
}