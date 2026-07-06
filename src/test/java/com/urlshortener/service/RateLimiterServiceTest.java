package com.urlshortener.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private RateLimiterService rateLimiterService;

    @Test
    @SuppressWarnings("unchecked")
    void allowsRequestWhenUnderLimit() {
        when(redisTemplate.execute((RedisScript<Long>) any(), anyList(), any())).thenReturn(5L);

        boolean allowed = rateLimiterService.isAllowed("ratelimit:test:1", 10, 60);

        assertThat(allowed).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsRequestWhenOverLimit() {
        when(redisTemplate.execute((RedisScript<Long>) any(), anyList(), any())).thenReturn(11L);

        boolean allowed = rateLimiterService.isAllowed("ratelimit:test:2", 10, 60);

        assertThat(allowed).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowsRequestAtExactLimitBoundary() {
        when(redisTemplate.execute((RedisScript<Long>) any(), anyList(), any())).thenReturn(10L);

        boolean allowed = rateLimiterService.isAllowed("ratelimit:test:3", 10, 60);

        assertThat(allowed).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsOpenWhenRedisIsUnavailable() {
        when(redisTemplate.execute((RedisScript<Long>) any(), anyList(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        // A rate limiter outage must never block legitimate traffic — fail open,
        // not closed. See RateLimiterService class javadoc for the full rationale.
        boolean allowed = rateLimiterService.isAllowed("ratelimit:test:4", 10, 60);

        assertThat(allowed).isTrue();
    }
}
