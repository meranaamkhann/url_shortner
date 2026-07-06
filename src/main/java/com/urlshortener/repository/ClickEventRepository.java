package com.urlshortener.repository;

import com.urlshortener.domain.entity.ClickEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    Page<ClickEvent> findByUrlIdOrderByClickedAtDesc(UUID urlId, Pageable pageable);

    long countByUrlIdAndClickedAtBetween(UUID urlId, Instant from, Instant to);

    @Query("select count(distinct c.ipHash) from ClickEvent c where c.urlId = :urlId and c.clickedAt between :from and :to")
    long countDistinctIpByUrlIdAndClickedAtBetween(@Param("urlId") UUID urlId,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to);

    @Query("select c.countryCode, count(c) from ClickEvent c where c.urlId = :urlId group by c.countryCode order by count(c) desc")
    List<Object[]> countByCountryForUrl(@Param("urlId") UUID urlId);

    @Query("select c.deviceType, count(c) from ClickEvent c where c.urlId = :urlId group by c.deviceType order by count(c) desc")
    List<Object[]> countByDeviceTypeForUrl(@Param("urlId") UUID urlId);

    @Query("select c.referrer, count(c) from ClickEvent c where c.urlId = :urlId and c.referrer is not null group by c.referrer order by count(c) desc")
    List<Object[]> countByReferrerForUrl(@Param("urlId") UUID urlId, Pageable pageable);

    /** Used by the rollup job (Kafka consumer or scheduled batch) to build url_analytics_daily rows. */
    @Query("select count(c) from ClickEvent c where c.urlId = :urlId and c.clickedAt >= :dayStart and c.clickedAt < :dayEnd and c.bot = false")
    long countNonBotClicksForDay(@Param("urlId") UUID urlId, @Param("dayStart") Instant dayStart, @Param("dayEnd") Instant dayEnd);
}
