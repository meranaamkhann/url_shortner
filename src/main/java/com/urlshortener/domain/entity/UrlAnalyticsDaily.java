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

    // Mapped to column "stat_date" rather than "day": DAY is a reserved keyword in H2
    // (used for the fast, Testcontainers-free repository test suite), and using it as
    // an unquoted identifier fails schema creation there even though Postgres accepts it.
    // The Java property name stays `day` on purpose — Spring Data derived query methods
    // (see UrlAnalyticsDailyRepository#findByUrlIdAndDay) key off the property name, not
    // the column name, so nothing else needs to change.
    @Column(name = "stat_date", nullable = false)
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
