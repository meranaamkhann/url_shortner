package com.urlshortener.repository;

import com.urlshortener.domain.entity.UrlAnalyticsDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UrlAnalyticsDailyRepository extends JpaRepository<UrlAnalyticsDaily, Long> {

    Optional<UrlAnalyticsDaily> findByUrlIdAndDay(UUID urlId, LocalDate day);

    List<UrlAnalyticsDaily> findByUrlIdAndDayBetweenOrderByDayAsc(UUID urlId, LocalDate from, LocalDate to);
}
