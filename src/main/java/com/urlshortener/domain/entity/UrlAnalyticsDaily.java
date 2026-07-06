package com.urlshortener.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "url_analytics_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlAnalyticsDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url_id", nullable = false)
    private UUID urlId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "total_clicks", nullable = false)
    @Builder.Default
    private long totalClicks = 0L;

    @Column(name = "unique_clicks", nullable = false)
    @Builder.Default
    private long uniqueClicks = 0L;

    @Column(name = "bot_clicks", nullable = false)
    @Builder.Default
    private long botClicks = 0L;
}
