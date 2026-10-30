package com.gambitrate.ingest;

import com.gambitrate.config.ChessComProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Component
public class RedisConditionalRequestStore {

    private static final String KEY_PREFIX = "gambitrate:chesscom:etag:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisConditionalRequestStore(
            StringRedisTemplate redisTemplate,
            ChessComProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.etagTtl();
    }

    public Optional<String> getEtag(URI uri) {
        String value = redisTemplate.opsForValue().get(key(uri));
        return value == null ? Optional.empty() : Optional.of(value);
    }

    public void putEtag(URI uri, String etag) {
        if (etag == null || etag.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(key(uri), etag, ttl);
    }

    private static String key(URI uri) {
        return KEY_PREFIX + uri;
    }
}
