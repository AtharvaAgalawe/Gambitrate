package com.gambitrate.ingest;

import com.gambitrate.config.ChessComProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisOutboundRateLimiterIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisOutboundRateLimiter rateLimiter;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        ChessComProperties properties = new ChessComProperties(
                "https://api.chess.com",
                "GambitRate/1.0 (contact: test@example.com)",
                Duration.ZERO,
                4,
                Duration.ofDays(3650),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofDays(7),
                Duration.ofSeconds(10),
                Duration.ofMillis(300)
        );
        rateLimiter = new RedisOutboundRateLimiter(redisTemplate, properties);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void execute_allowsOnlyOneCallerAcrossInstances() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<String> holder = executor.submit(() -> rateLimiter.execute(() -> {
            acquired.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return "held";
        }));

        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        assertThatThrownBy(() -> rateLimiter.execute(() -> "blocked"))
                .isInstanceOf(ChessComRateLimitException.class);
        long waitedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(waitedMs).isGreaterThanOrEqualTo(250);

        release.countDown();
        assertThat(holder.get(2, TimeUnit.SECONDS)).isEqualTo("held");
        assertThat(rateLimiter.execute(() -> "after")).isEqualTo("after");
        executor.shutdownNow();
    }
}
