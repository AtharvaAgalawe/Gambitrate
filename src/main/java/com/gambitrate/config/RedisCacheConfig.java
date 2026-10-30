package com.gambitrate.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Map;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    public static final String PAST_MONTH_CACHE = "chesscomPastMonths";
    public static final String CURRENT_MONTH_CACHE = "chesscomCurrentMonth";
    public static final String ARCHIVES_CACHE = "chesscomArchives";

    @Bean
    CacheManager cacheManager(RedisConnectionFactory connectionFactory, ChessComProperties properties) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        PAST_MONTH_CACHE, defaults.entryTtl(properties.pastMonthTtl()),
                        CURRENT_MONTH_CACHE, defaults.entryTtl(properties.currentMonthTtl()),
                        ARCHIVES_CACHE, defaults.entryTtl(properties.archivesTtl())
                ))
                .disableCreateOnMissingCache()
                .build();
    }
}
