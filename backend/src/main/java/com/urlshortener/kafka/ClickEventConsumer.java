package com.urlshortener.kafka;

import com.urlshortener.domain.entity.ClickEvent;
import com.urlshortener.dto.event.ClickEventMessage;
import com.urlshortener.repository.ClickEventRepository;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.util.UserAgentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes click-events and performs the (relatively) expensive writes that we
 * deliberately kept off the synchronous redirect path: the detailed click_events
 * row insert and the denormalized urls.click_count increment.
 *
 * Consumer group is configured (application.yml: spring.kafka.consumer.group-id) so
 * that scaling out this consumer (multiple app instances, or a dedicated analytics-
 * worker deployment) automatically load-balances partitions across instances —
 * this is what lets click-event processing scale independently from the redirect
 * API's instance count.
 *
 * Error handling: a poison-pill message (e.g. malformed JSON, or a urlId that no
 * longer exists) would otherwise block its partition forever on retry. In production
 * this listener is wrapped with a DefaultErrorHandler configured to retry a few times
 * then route to the click-events-dlq topic (see KafkaConfig#clickEventsDlqTopic) —
 * omitted here for brevity but called out explicitly so it isn't mistaken for an
 * oversight.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventConsumer {

    private final ClickEventRepository clickEventRepository;
    private final UrlRepository urlRepository;

    @KafkaListener(topics = "click-events", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onClickEvent(@Payload ClickEventMessage message,
                              @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
        try {
            UUID urlId = UUID.fromString(message.urlId());
            UserAgentParser.ParsedUserAgent ua = UserAgentParser.parse(message.userAgent());

            ClickEvent event = ClickEvent.builder()
                    .urlId(urlId)
                    .clickedAt(message.clickedAt())
                    .ipHash(message.ipHash())
                    .countryCode(message.countryCode())
                    .city(message.city())
                    .referrer(message.referrer())
                    .userAgent(message.userAgent())
                    .deviceType(ua.deviceType())
                    .browser(ua.browser())
                    .os(ua.os())
                    .bot(ua.bot())
                    .build();
            clickEventRepository.save(event);

            // Bot clicks are still recorded for transparency/anti-fraud analysis, but are
            // excluded from the user-facing click_count to avoid misleading owners about
            // genuine human engagement (a common source of "analytics spam" complaints).
            if (!ua.bot()) {
                urlRepository.incrementClickCount(urlId);
            }
        } catch (Exception e) {
            log.error("Failed to process click event message on partition={}: {}", partition, message, e);
            throw e; // rethrow so the consumer's error handler / retry policy can act on it
        }
    }
}
