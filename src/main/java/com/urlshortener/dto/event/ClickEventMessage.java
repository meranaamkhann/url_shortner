package com.urlshortener.dto.event;

import java.io.Serializable;
import java.time.Instant;

/**
 * Kafka message published on every redirect (Phase 4). Keeping this on the hot
 * redirect path as a fire-and-forget publish (not a synchronous DB write) is what
 * lets the redirect endpoint stay sub-10ms — the heavier analytics writes happen
 * asynchronously, decoupled from user-facing latency.
 */
public record ClickEventMessage(
        String urlId,
        String shortCode,
        Instant clickedAt,
        String ipHash,
        String userAgent,
        String referrer,
        String countryCode,
        String city
) implements Serializable {
}
