package com.urlshortener.domain.entity;

import com.urlshortener.domain.enums.UrlStatus;
import com.urlshortener.domain.enums.Visibility;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * The core entity of the system. Soft-delete is implemented via {@code deletedAt}
 * rather than physically removing rows immediately — this preserves analytics
 * history and gives us an undo window, with a scheduled job performing the
 * actual hard delete after a retention period (see UrlService#hardDeleteExpiredSoftDeletes).
 *
 * NOTE (deliberate design decision): we intentionally do NOT use Hibernate's
 * {@code @SQLRestriction}/{@code @Where} entity-level filter for "deletedAt IS NULL".
 * That annotation applies *globally* to every query Hibernate generates for this
 * entity — including admin/restore/hard-delete-sweep queries — which would silently
 * hide the very rows those jobs need to operate on. Instead, every repository
 * query is explicit about whether it includes or excludes soft-deleted rows.
 * Explicit is better than implicit for anything touching data deletion.
 */
@Entity
@Table(name = "urls")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Url extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    /** SHA-256 hex digest of longUrl, used for O(1) duplicate-URL lookups instead of scanning TEXT columns. */
    @Column(name = "long_url_hash", nullable = false, length = 64)
    private String longUrlHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id")
    private CustomDomain domain;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "is_custom_alias", nullable = false)
    @Builder.Default
    private boolean customAlias = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Visibility visibility = Visibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UrlStatus status = UrlStatus.ACTIVE;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "max_clicks")
    private Long maxClicks;

    @Column(name = "click_count", nullable = false)
    @Builder.Default
    private long clickCount = 0L;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ---- domain behaviour (keeps business rules out of the service layer where possible) ----

    public boolean isExpired() {
        if (status == UrlStatus.EXPIRED) return true;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return true;
        if (maxClicks != null && clickCount >= maxClicks) return true;
        return false;
    }

    public boolean isRedirectable() {
        return status == UrlStatus.ACTIVE && deletedAt == null && !isExpired();
    }

    public boolean isPasswordProtected() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
