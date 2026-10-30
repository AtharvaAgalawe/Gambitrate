package com.gambitrate.ingest;

import com.gambitrate.config.ChessComProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class RedisOutboundRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisOutboundRateLimiter.class);
    private static final String HOST_LOCK_KEY = "gambitrate:chesscom:host-lock";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Duration lease;
    private final Duration wait;

    public RedisOutboundRateLimiter(StringRedisTemplate redisTemplate, ChessComProperties properties) {
        this.redisTemplate = redisTemplate;
        this.lease = properties.hostLockLease();
        this.wait = properties.hostLockWait();
    }

    public <T> T execute(Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        acquire(token);
        try {
            return action.get();
        } finally {
            release(token);
        }
    }

    private void acquire(String token) {
        long start = System.nanoTime();
        long deadline = start + wait.toNanos();
        do {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(HOST_LOCK_KEY, token, lease);
            if (Boolean.TRUE.equals(acquired)) {
                long waitedMs = (System.nanoTime() - start) / 1_000_000;
                if (waitedMs > 100) {
                    log.info("outbound_host_gate_acquired wait_ms={}", waitedMs);
                }
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ChessComUpstreamException(
                        "Interrupted while waiting for the chess.com outbound gate.", exception);
            }
        } while (System.nanoTime() < deadline);

        long retryAfter = Math.max(1, wait.toSeconds());
        throw new ChessComRateLimitException(retryAfter);
    }

    private void release(String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(HOST_LOCK_KEY), token);
        } catch (RuntimeException exception) {
            log.warn("outbound_host_gate_release_failed message={}", exception.getMessage());
        }
    }
}
