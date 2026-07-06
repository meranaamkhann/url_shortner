package com.urlshortener.kafka;

import com.urlshortener.dto.event.AuditEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Downstream consumer of the audit-events stream. In production this would forward to
 * a SIEM (Splunk/Datadog Security/ELK) or trigger real-time fraud-detection rules (e.g.
 * "5 failed logins from the same IP in 1 minute -> auto-block"). Implemented here as a
 * structured log line so the event flow (producer -> Kafka -> consumer) is fully
 * demonstrable end-to-end without standing up an external SIEM for this project.
 */
@Slf4j
@Component
public class AuditEventConsumer {

    @KafkaListener(topics = "audit-events", groupId = "url-shortener-security-consumer")
    public void onAuditEvent(AuditEventMessage message) {
        log.info("[SECURITY-AUDIT] action={} actorId={} entityType={} entityId={} ip={} occurredAt={}",
                message.action(), message.actorId(), message.entityType(), message.entityId(),
                message.ipAddress(), message.occurredAt());
        // Production hook point: forward `message` to a SIEM client, evaluate fraud rules,
        // or trigger alerting (e.g. PagerDuty) for high-severity actions.
    }
}
