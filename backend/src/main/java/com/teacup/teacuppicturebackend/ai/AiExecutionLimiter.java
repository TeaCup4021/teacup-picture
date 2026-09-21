package com.teacup.teacuppicturebackend.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AiExecutionLimiter {
    private static final DefaultRedisScript<Long> ACQUIRE_CONCURRENCY = new DefaultRedisScript<>("""
            local current = redis.call('TIME')
            local now = current[1] * 1000 + math.floor(current[2] / 1000)
            local limit = tonumber(ARGV[1])
            local lease = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZCARD', KEYS[1]) >= limit then
                return 0
            end
            redis.call('ZADD', KEYS[1], now + lease, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], lease * 2)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ACQUIRE_RATE = new DefaultRedisScript<>("""
            local current = redis.call('TIME')
            local now = current[1] * 1000 + math.floor(current[2] / 1000)
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local updated = tonumber(redis.call('HGET', KEYS[1], 'updated'))
            if tokens == nil then tokens = capacity end
            if updated == nil then updated = now end
            tokens = math.min(capacity, tokens + ((now - updated) * refill))
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil((capacity / refill) * 2))
            return allowed
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[2]) == false then
                return 0
            end
            local current = redis.call('TIME')
            local now = current[1] * 1000 + math.floor(current[2] / 1000)
            local lease = tonumber(ARGV[1])
            redis.call('ZADD', KEYS[1], 'XX', now + lease, ARGV[2])
            redis.call('PEXPIRE', KEYS[1], lease * 2)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "return redis.call('ZREM', KEYS[1], ARGV[1])", Long.class);

    private final StringRedisTemplate redis;
    private final int globalConcurrency;
    private final int requestsPerMinute;
    private final int burstCapacity;
    private final long leaseMillis;

    public AiExecutionLimiter(StringRedisTemplate redis,
                              @Value("${teacup.ai.worker.global-concurrency:12}") int globalConcurrency,
                              @Value("${teacup.ai.worker.requests-per-minute:60}") int requestsPerMinute,
                              @Value("${teacup.ai.worker.burst-capacity:4}") int burstCapacity,
                              @Value("${teacup.ai.worker.lease-seconds:300}") long leaseSeconds) {
        this.redis = redis;
        this.globalConcurrency = positive(globalConcurrency, "global concurrency");
        this.requestsPerMinute = positive(requestsPerMinute, "requests per minute");
        this.burstCapacity = positive(burstCapacity, "burst capacity");
        this.leaseMillis = positive(leaseSeconds, "lease seconds") * 1000L;
    }

    public Optional<Permit> tryAcquire(String provider) {
        String normalized = provider == null || provider.isBlank() ? "unknown" : provider.toLowerCase();
        String concurrencyKey = "teacup:ai:concurrency:" + normalized;
        String rateKey = "teacup:ai:rate:" + normalized;
        String token = UUID.randomUUID().toString();
        try {
            Long concurrency = redis.execute(ACQUIRE_CONCURRENCY, List.of(concurrencyKey),
                    Integer.toString(globalConcurrency), Long.toString(leaseMillis), token);
            if (!Long.valueOf(1L).equals(concurrency)) return Optional.empty();
            double refillPerMillis = requestsPerMinute / 60_000.0d;
            Long rate = redis.execute(ACQUIRE_RATE, List.of(rateKey),
                    Integer.toString(burstCapacity), Double.toString(refillPerMillis));
            if (!Long.valueOf(1L).equals(rate)) {
                redis.execute(RELEASE, List.of(concurrencyKey), token);
                return Optional.empty();
            }
            return Optional.of(new Permit(concurrencyKey, token));
        } catch (RuntimeException exception) {
            try {
                redis.execute(RELEASE, List.of(concurrencyKey), token);
            } catch (RuntimeException ignored) {
                // The caller will retry; permit expiry is the final safety net.
            }
            throw new AiLimiterUnavailableException("AI execution limiter is unavailable", exception);
        }
    }

    public final class Permit implements AutoCloseable {
        private final String key;
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(String key, String token) {
            this.key = key;
            this.token = token;
        }

        public boolean renew() {
            if (closed.get()) return false;
            Long renewed = redis.execute(RENEW, List.of(key), Long.toString(leaseMillis), token);
            return Long.valueOf(1L).equals(renewed);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    redis.execute(RELEASE, List.of(key), token);
                } catch (RuntimeException ignored) {
                    // The lease expires even if Redis becomes unavailable during release.
                }
            }
        }
    }

    public static class AiLimiterUnavailableException extends RuntimeException {
        public AiLimiterUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static int positive(int value, String label) {
        if (value <= 0) throw new IllegalArgumentException("AI worker " + label + " must be positive");
        return value;
    }

    private static long positive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException("AI worker " + label + " must be positive");
        return value;
    }
}
