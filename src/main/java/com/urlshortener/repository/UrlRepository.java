package com.urlshortener.repository;

import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.enums.UrlStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UrlRepository extends JpaRepository<Url, UUID> {

    /**
     * Lookup for the default (no custom domain) namespace. This is the hot path
     * hit on every redirect, so it MUST be covered by the unique index defined
     * in V1__init_schema.sql (uq_urls_domain_shortcode).
     */
    @Query("select u from Url u where u.shortCode = :shortCode and u.domain is null and u.deletedAt is null")
    Optional<Url> findByShortCodeAndDefaultDomain(@Param("shortCode") String shortCode);

    @Query("select u from Url u where u.shortCode = :shortCode and u.domain.id = :domainId and u.deletedAt is null")
    Optional<Url> findByShortCodeAndDomainId(@Param("shortCode") String shortCode, @Param("domainId") UUID domainId);

    boolean existsByShortCodeAndDomainIsNullAndDeletedAtIsNull(String shortCode);

    /** Used for duplicate-URL detection: same owner shortening the same long URL again. */
    Optional<Url> findFirstByLongUrlHashAndOwnerIdAndStatusAndDeletedAtIsNull(String longUrlHash, UUID ownerId, UrlStatus status);

    /** Anonymous duplicate detection (no owner) within a short dedup window is handled at the service layer via Redis. */
    Optional<Url> findFirstByLongUrlHashAndOwnerIsNullAndStatusAndDeletedAtIsNull(String longUrlHash, UrlStatus status);

    Page<Url> findByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

    Page<Url> findByOwnerIdAndStatusAndDeletedAtIsNull(UUID ownerId, UrlStatus status, Pageable pageable);

    /**
     * Atomic counter increment — avoids the classic "read click_count, add 1, write back"
     * race condition under concurrent redirects. A single UPDATE...SET x = x + 1 is
     * executed entirely inside the database and is safe under any level of concurrency.
     */
    @Modifying
    @Query("update Url u set u.clickCount = u.clickCount + 1 where u.id = :id")
    int incrementClickCount(@Param("id") UUID id);

    @Modifying
    @Query("update Url u set u.status = :status where u.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") UrlStatus status);

    @Modifying
    @Query("update Url u set u.deletedAt = :now, u.status = 'DELETED' where u.id = :id")
    int softDelete(@Param("id") UUID id, @Param("now") Instant now);

    /** Scheduled job target: URLs whose expiry passed but are still marked ACTIVE. */
    @Query("select u from Url u where u.status = 'ACTIVE' and u.expiresAt is not null and u.expiresAt < :now and u.deletedAt is null")
    List<Url> findExpiredButStillActive(@Param("now") Instant now, Pageable pageable);

    /** Hard-delete sweep target: soft-deleted rows past the retention window. */
    @Query(value = "select u.id from Url u where u.deletedAt is not null and u.deletedAt < :cutoff")
    List<UUID> findSoftDeletedBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = "delete from Url u where u.id in :ids")
    void hardDeleteByIds(@Param("ids") List<UUID> ids);
}
