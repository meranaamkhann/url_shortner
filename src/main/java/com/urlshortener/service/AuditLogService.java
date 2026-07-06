package com.urlshortener.service;

import com.urlshortener.domain.entity.AuditLog;
import com.urlshortener.domain.enums.AuditAction;
import com.urlshortener.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes to the audit_logs table. Calls are @Async so audit logging never adds
 * latency to the request that triggered it, and a slow/contended audit table can
 * never become a bottleneck for user-facing operations. In Phase 4 this is fed
 * from Kafka's audit-events topic instead of being called directly (see
 * AuditEventConsumer) — both paths converge on the same persistence logic here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(UUID actorId, AuditAction action, String entityType, String entityId,
                     String ipAddress, Map<String, Object> metadata) {
        try {
            AuditLog entry = AuditLog.builder()
                    .actorId(actorId)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .ipAddress(ipAddress)
                    .metadata(metadata)
                    .createdAt(Instant.now())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Audit logging is best-effort by design: it must never roll back or block
            // the business transaction that triggered it.
            log.error("Failed to persist audit log entry for action={} entityType={} entityId={}",
                    action, entityType, entityId, e);
        }
    }
}
