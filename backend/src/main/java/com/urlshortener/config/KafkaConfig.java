package com.urlshortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka usage in this system (Phase 4) — full discussion in docs/ARCHITECTURE.md:
 *
 *  - click-events topic: every redirect publishes a ClickEventMessage here instead of
 *    writing synchronously to Postgres. This decouples the user-facing redirect latency
 *    from analytics write throughput, and lets us absorb huge click bursts (a viral link)
 *    by buffering in Kafka and consuming at a sustainable rate, rather than overwhelming
 *    the database with a write spike.
 *  - audit-events topic: security-sensitive actions (login, URL deletion, etc.) are
 *    published here; a consumer persists them to the audit_logs table. Same decoupling
 *    benefit, plus it would let us fan out to a SIEM system later without touching
 *    the producing code at all.
 *  - Partitioning: click-events is partitioned by urlId (see ClickEventProducer), which
 *    guarantees all events for a given URL are processed in order by the same consumer
 *    thread — important if we ever build same-URL sequential aggregation logic.
 *  - Idempotent producer (enable.idempotence=true, acks=all) avoids duplicate click
 *    counts from producer retries during transient broker issues.
 *
 *  Replication factor is externalised (default 1) so this boots against the single-broker
 *  docker-compose Kafka used for local dev/demo; set KAFKA_REPLICATION_FACTOR=3 in any
 *  real multi-broker (staging/prod) cluster for actual fault tolerance.
 */
@Configuration
public class KafkaConfig {

    @Value("${app.kafka.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public NewTopic clickEventsTopic() {
        return TopicBuilder.name("click-events")
                .partitions(6)
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7 days
                .build();
    }

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name("audit-events")
                .partitions(3)
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(90L * 24 * 60 * 60 * 1000)) // 90 days, compliance
                .build();
    }

    @Bean
    public NewTopic clickEventsDlqTopic() {
        // Dead-letter queue: events that repeatedly fail consumer processing land here
        // instead of blocking the partition or being silently dropped.
        return TopicBuilder.name("click-events-dlq")
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }
}
