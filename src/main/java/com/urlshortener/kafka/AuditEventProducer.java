package com.urlshortener.kafka;

import com.urlshortener.dto.event.AuditEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes security-relevant events (login failures, account lockouts, suspicious-URL
 * rejections, mass deletions) to the audit-events topic. AuditLogService remains the
 * primary synchronous-ish write path backing the in-app "recent activity" view; this
 * Kafka fan-out exists so downstream systems — a SIEM, a fraud-detection pipeline, a
 * security team's alerting rules — can consume the same event stream independently,
 * without ever touching our database or coupling their uptime to ours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventProducer {

    private static final String TOPIC = "audit-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(AuditEventMessage message) {
        String key = message.actorId() != null ? message.actorId() : "anonymous";
        kafkaTemplate.send(TOPIC, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish audit event action={} entityId={}",
                                message.action(), message.entityId(), ex);
                    }
                });
    }
}
