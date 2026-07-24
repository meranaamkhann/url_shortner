package com.urlshortener.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Distributed rate limiter (Phase 4).
 *
 * Algorithm: fixed-window counter, implemented as a single atomic Redis Lua script
 * (INCR + conditional EXPIRE). Chosen over a naive "GET then SET" because that pattern
 * has a check-then-act race: two concurrent requests can both read count=N and both
 * decide to proceed, silently doubling the effective limit. Running the increment and
 * the limit check inside one EVAL makes it atomic regardless of how many app instances
 * are issuing requests concurrently — which is exactly the property we need a rate
 * limiter to have under horizontal scaling.
 *
 * Trade-off vs. a sliding-window-log or token-bucket algorithm: fixed windows allow a
 * burst of up to 2x the limit right at a window boundary (e.g. 100 requests at 0:59 and
 * another 100 at 1:00). That's an accepted trade-off here for the O(1) memory/CPU cost;
 * a token bucket (e.g. via bucket4j-redis) would smooth that boundary burst at the cost
 * of a slightly more complex Lua script and bucket-state structure. Documented as the
 * natural "next iteration" in docs/SCALING.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String LUA_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private final DefaultRedisScript<Long> script = buildScript();

    private DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(LUA_SCRIPT);
        s.setResultType(Long.class);
        return s;
    }

    /**
     * @param key        e.g. "ratelimit:shorten:{ip}" or "ratelimit:auth:{userId}"
     * @param limit      max requests allowed within the window
     * @param windowSecs window length in seconds
     * @return true if the request is allowed, false if the limit has been exceeded
     */
    public boolean isAllowed(String key, long limit, long windowSecs) {
        try {
            Long count = redisTemplate.execute(script, List.of(key), String.valueOf(windowSecs));
            return count != null && count <= limit;
        } catch (Exception e) {
            // Fail-open on Redis unavailability: a rate limiter outage should degrade
            // gracefully rather than take down the entire API. This is logged loudly so
            // on-call is aware abuse protection is temporarily down, and the circuit
            // breaker around Redis (see CacheService) will keep retrying in the background.
            log.error("Rate limiter Redis call failed for key={}, failing OPEN (allowing request)", key, e);
            return true;
        }
    }

    public long remainingTtlSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key);
        return ttl == null ? -1 : ttl;
    }
}
