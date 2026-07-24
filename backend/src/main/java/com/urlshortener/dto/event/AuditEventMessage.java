package com.urlshortener.dto.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

public record AuditEventMessage(
        String actorId,
        String action,
        String entityType,
        String entityId,
        String ipAddress,
        Map<String, Object> metadata,
        Instant occurredAt
) implements Serializable {
}
