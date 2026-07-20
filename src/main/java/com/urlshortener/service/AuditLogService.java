package com.urlshortener.service;

import com.urlshortener.domain.enums.AuditAction;
import com.urlshortener.dto.event.AuditEventMessage;
import com.urlshortener.kafka.AuditEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Public entry point every service/controller calls to record an audit event. This
 * class is deliberately a thin facade: it does NOT write to the audit_logs table
 * itself. Instead it publishes onto the audit-events Kafka topic (see KafkaConfig)
 * and returns immediately — actual persistence happens in AuditEventConsumer.
 *
 * Why route even same-process audit writes through Kafka rather than calling the
 * repository directly:
 *  - One code path, one behavior. Whether an audit event originates from this
 *    in-process call or (in a future service split) from another microservice
 *    entirely, it goes through the exact same topic and the exact same consumer
 *    logic — there's no risk of the two paths silently drifting apart over time.
 *  - The same stream a SIEM/fraud-detection consumer would tap into (see
 *    AuditEventConsumer's class javadoc) is guaranteed to see 100% of audit
 *    events, not just the ones some other path remembered to also publish.
 *  - A slow or momentarily-unavailable audit_logs table can never add latency to
 *    the request that triggered the audit event — publishing to Kafka is a fast,
 *    fire-and-forget call, and the consumer absorbs any downstream slowness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditEventProducer auditEventProducer;

    public void log(UUID actorId, AuditAction action, String entityType, String entityId,
                     String ipAddress, Map<String, Object> metadata) {
        try {
            AuditEventMessage message = new AuditEventMessage(
                    actorId != null ? actorId.toString() : null,
                    action.name(),
                    entityType,
                    entityId,
                    ipAddress,
                    metadata,
                    Instant.now()
            );
            auditEventProducer.publish(message);
        } catch (Exception e) {
            // Audit logging is best-effort by design: it must never roll back or block
            // the business transaction that triggered it.
            log.error("Failed to publish audit event for action={} entityType={} entityId={}",
                    action, entityType, entityId, e);
        }
    }
}
