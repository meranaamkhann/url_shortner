package com.urlshortener.domain.entity;

import com.urlshortener.domain.enums.DeviceType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "click_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url_id", nullable = false)
    private UUID urlId;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    /** SHA-256 of the client IP. Raw IPs are never persisted (GDPR / privacy by design). */
    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(length = 100)
    private String city;

    @Column(length = 512)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 20)
    private DeviceType deviceType;

    @Column(length = 50)
    private String browser;

    @Column(length = 50)
    private String os;

    @Column(name = "is_bot", nullable = false)
    @Builder.Default
    private boolean bot = false;
}
