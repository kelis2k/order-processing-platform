package ru.potekhincode.notification.rateLimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class EmailRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;

    private final boolean enabled;
    private final long capacity;
    private final long windowMs;

    public EmailRateLimiter(StringRedisTemplate redis,
                            RedisScript<Long> emailRateLimiterScript,
                            @Value("${app.rate-limit.enabled}") boolean enabled,
                            @Value("${app.rate-limit.capacity}") long capacity,
                            @Value("${app.rate-limit.window-ms}") long windowMs) {
        this.redis = redis;
        this.script = emailRateLimiterScript;
        this.enabled = enabled;
        this.capacity = capacity;
        this.windowMs = windowMs;
    }


    public boolean tryAcquire(String userId) {
        if (!enabled) {
            return true;
        }
        try {
            Long allowed = redis.execute(script, List.of(key(userId)),
                    String.valueOf(capacity),
                    String.valueOf(windowMs),
                    String.valueOf(System.currentTimeMillis()),
                    "1");
            return allowed != null && allowed == 1L;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing send (fail-open): {}", e.getMessage());
            return true;
        }
    }

    private String key(String userId) {
        return "notif:rl:" + userId;
    }
}
