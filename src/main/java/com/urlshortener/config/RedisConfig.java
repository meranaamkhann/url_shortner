package com.urlshortener.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Caching strategy (Phase 3) — see docs/ARCHITECTURE.md "Caching Strategy" for the full
 * write-up. In short:
 *
 *  - "urls" cache: shortCode -> Url DTO, the redirect hot path. Cache-aside pattern:
 *    read-through on miss, write/evict on update/disable/delete. Short TTL (1h) bounds
 *    staleness if an invalidation is ever missed (e.g. multi-instance race).
 *  - A separate short negative-cache TTL absorbs "URL not found" / 404 floods from
 *    enumeration attacks and bots probing random short codes, so they hit Redis
 *    instead of Postgres on every guess (see CacheService#cacheNegative).
 *  - Rate limiting (Bucket4j) also uses Redis as its distributed counter store so rate
 *    limits are enforced consistently across all horizontally-scaled app instances,
 *    not per-instance (which would let a client multiply their effective limit by N
 *    just by spreading requests across N pods).
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer()));

        Map<String, RedisCacheConfiguration> perCacheConfig = new HashMap<>();
        perCacheConfig.put("urls", defaultConfig.entryTtl(Duration.ofHours(1)));
        perCacheConfig.put("urlsNegative", defaultConfig.entryTtl(Duration.ofSeconds(60)));
        perCacheConfig.put("analyticsSummary", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        perCacheConfig.put("linkPreview", defaultConfig.entryTtl(Duration.ofHours(24)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(perCacheConfig)
                .build();
    }

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
