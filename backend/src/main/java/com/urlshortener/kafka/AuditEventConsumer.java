package com.urlshortener.kafka;

import com.urlshortener.domain.entity.AuditLog;
import com.urlshortener.domain.enums.AuditAction;
import com.urlshortener.dto.event.AuditEventMessage;
import com.urlshortener.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The persistence half of the audit pipeline: every message published by
 * AuditLogService (via AuditEventProducer) lands here and is written to the
 * audit_logs table — this is what actually backs the /api/v1/admin/audit-logs
 * endpoint and any future compliance export.
 *
 * This is also the natural attachment point for downstream consumers that don't
 * exist yet but are one KafkaListener away: forwarding to a SIEM (Splunk/Datadog
 * Security/ELK), or real-time fraud-detection rules (e.g. "5 failed logins from
 * the same IP in 1 minute -> auto-block"). Because the topic already carries
 * every audit event in the system, adding those consumers later never requires
 * touching the producing code again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventConsumer {

    private final AuditLogRepository auditLogRepository;

    @KafkaListener(topics = "audit-events", groupId = "url-shortener-security-consumer")
    @Transactional
    public void onAuditEvent(AuditEventMessage message) {
        try {
            AuditLog entry = AuditLog.builder()
                    .actorId(message.actorId() != null ? UUID.fromString(message.actorId()) : null)
                    .action(AuditAction.valueOf(message.action()))
                    .entityType(message.entityType())
                    .entityId(message.entityId())
                    .ipAddress(message.ipAddress())
                    .metadata(message.metadata())
                    .createdAt(message.occurredAt())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Consistent with ClickEventConsumer: log loudly and rethrow so the consumer's
            // error handler / retry policy (and eventually the DLQ) can act on a malformed
            // or otherwise unprocessable message, rather than silently dropping an audit
            // record — audit trail completeness matters more than throughput here.
            log.error("Failed to persist audit event action={} entityType={} entityId={}: {}",
                    message.action(), message.entityType(), message.entityId(), message, e);
            throw e;
        }
    }
}
