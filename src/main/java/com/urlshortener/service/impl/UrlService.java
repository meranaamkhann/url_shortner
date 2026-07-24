package com.urlshortener.service.impl;

import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.entity.User;
import com.urlshortener.domain.enums.AuditAction;
import com.urlshortener.domain.enums.UrlStatus;
import com.urlshortener.domain.enums.Visibility;
import com.urlshortener.dto.event.ClickEventMessage;
import com.urlshortener.dto.request.BulkCreateUrlRequest;
import com.urlshortener.dto.request.CreateUrlRequest;
import com.urlshortener.dto.request.UpdateUrlRequest;
import com.urlshortener.dto.response.PagedResponse;
import com.urlshortener.dto.response.UrlResponse;
import com.urlshortener.exception.*;
import com.urlshortener.kafka.ClickEventProducer;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.security.AuthenticatedPrincipal;
import com.urlshortener.service.AuditLogService;
import com.urlshortener.service.CacheService;
import com.urlshortener.service.GeoLocationService;
import com.urlshortener.service.ShortCodeGeneratorService;
import com.urlshortener.util.AliasValidator;
import com.urlshortener.util.HashUtil;
import com.urlshortener.util.IpAddressUtil;
import com.urlshortener.util.UrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final ShortCodeGeneratorService codeGenerator;
    private final UrlValidator urlValidator;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;
    private final ClickEventProducer clickEventProducer;
    private final GeoLocationService geoLocationService;
    private final AuditLogService auditLogService;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.cache.url-ttl-seconds:3600}")
    private long urlCacheTtlSeconds;

    @Value("${app.cache.negative-ttl-seconds:60}")
    private long negativeTtlSeconds;

    // ─── PHASE 1: Create & Redirect ──────────────────────────────────────────

    @Transactional
    public UrlResponse create(CreateUrlRequest request, AuthenticatedPrincipal principal, String ipAddress) {
        String sanitizedUrl = urlValidator.validateAndNormalize(request.longUrl());

        if (request.customAlias() != null) {
            AliasValidator.validate(request.customAlias());
        }

        User owner = principal != null
                ? userRepository.findById(principal.id()).orElse(null)
                : null;

        // Dedup: if this owner is re-shortening a URL they already shortened, return the existing one.
        // This prevents URL explosion from clients that call /shorten on every page load.
        String urlHash = HashUtil.sha256Hex(sanitizedUrl);
        if (owner != null) {
            var existing = urlRepository.findFirstByLongUrlHashAndOwnerIdAndStatusAndDeletedAtIsNull(
                    urlHash, owner.getId(), UrlStatus.ACTIVE);
            if (existing.isPresent() && request.customAlias() == null) {
                return toResponse(existing.get());
            }
        }

        String shortCode = resolveShortCode(request, owner);

        String passwordHash = null;
        if (request.password() != null && !request.password().isBlank()) {
            passwordHash = passwordEncoder.encode(request.password());
        }

        Visibility visibility = request.visibility() != null ? request.visibility() : Visibility.PUBLIC;
        if (owner == null) {
            visibility = Visibility.PUBLIC; // anonymous links are always public
        }

        Url url = Url.builder()
                .shortCode(shortCode)
                .longUrl(sanitizedUrl)
                .longUrlHash(urlHash)
                .owner(owner)
                .customAlias(request.customAlias() != null)
                .visibility(visibility)
                .status(UrlStatus.ACTIVE)
                .passwordHash(passwordHash)
                .expiresAt(request.expiresAt())
                .maxClicks(request.maxClicks())
                .build();

        url = urlRepository.save(url);

        if (owner != null) {
            auditLogService.log(owner.getId(), AuditAction.URL_CREATED, "Url", url.getId().toString(),
                    ipAddress, Map.of("shortCode", shortCode));
        }

        UrlResponse response = toResponse(url);
        cacheService.put(shortCode, response, Duration.ofSeconds(urlCacheTtlSeconds));
        return response;
    }

    private String resolveShortCode(CreateUrlRequest request, User owner) {
        if (request.customAlias() != null) {
            String alias = request.customAlias();
            if (urlRepository.existsByShortCodeAndDomainIsNullAndDeletedAtIsNull(alias)) {
                throw new DuplicateAliasException(alias);
            }
            return alias;
        }
        return codeGenerator.generateUniqueCode();
    }

    /**
     * The redirect hot path. Performance hierarchy (Phase 3):
     *   1. Redis cache hit  — O(1), sub-millisecond, most requests stop here
     *   2. Redis miss       — DB lookup, cache the result, respond
     *   3. Not found        — negative-cache for 60s, return 404
     *
     * Click tracking (Phase 4) is fire-and-forget via Kafka: we publish the event and
     * immediately return the redirect. Analytics lag behind the actual click by the
     * consumer's processing time, which is an acceptable trade-off for keeping redirect
     * latency under 10ms regardless of analytics write throughput.
     */
    @Transactional
    public String resolveAndTrack(String shortCode, HttpServletRequest request) {
        // 1. Cache lookup
        var cached = cacheService.get(shortCode);
        if (cacheService.isNegative(cached)) {
            throw new ResourceNotFoundException("Short URL not found: " + shortCode);
        }

        UrlResponse urlResponse = cached.orElse(null);
        Url url = null;

        if (urlResponse == null) {
            // 2. DB fallback
            url = urlRepository.findByShortCodeAndDefaultDomain(shortCode)
                    .orElseGet(() -> null);

            if (url == null || url.getDeletedAt() != null) {
                cacheService.cacheNegative(shortCode, Duration.ofSeconds(negativeTtlSeconds));
                throw new ResourceNotFoundException("Short URL not found: " + shortCode);
            }

            urlResponse = toResponse(url);
            cacheService.put(shortCode, urlResponse, Duration.ofSeconds(urlCacheTtlSeconds));
        }

        // 3. State checks (use response data to avoid an extra DB call when we had a cache hit)
        if (urlResponse.status() == UrlStatus.DISABLED) {
            throw new UrlDisabledException(shortCode);
        }
        if (urlResponse.status() == UrlStatus.DELETED) {
            throw new ResourceNotFoundException("Short URL not found: " + shortCode);
        }
        if (urlResponse.status() == UrlStatus.EXPIRED
                || (urlResponse.expiresAt() != null && Instant.now().isAfter(urlResponse.expiresAt()))) {
            throw new UrlExpiredException(shortCode);
        }

        // 4. Password-protected link check
        if (urlResponse.passwordProtected()) {
            // For password-protected links the caller should use the /access endpoint
            // which accepts the password in the request body. We return UNAUTHORIZED here
            // so the client knows to show a password form rather than immediately redirecting.
            throw new LinkPasswordRequiredException("This link requires a password to access.");
        }

        // 5. Fire-and-forget click event
        publishClickEvent(urlResponse, request);

        return urlResponse.longUrl();
    }

    /**
     * Verifies the presented password and returns the destination URL.
     * Separated from resolveAndTrack so password checking never happens
     * on the anonymous hot path (avoids a BCrypt verify on every redirect).
     */
    @Transactional
    public String resolveWithPassword(String shortCode, String password, HttpServletRequest request) {
        Url url = urlRepository.findByShortCodeAndDefaultDomain(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("Short URL not found: " + shortCode));

        if (!url.isRedirectable()) {
            throw new UrlDisabledException(shortCode);
        }
        if (!url.isPasswordProtected()) {
            // No password needed — behave like a normal redirect
            return resolveAndTrack(shortCode, request);
        }
        if (!passwordEncoder.matches(password, url.getPasswordHash())) {
            throw new InvalidCredentialsException("Incorrect link password.");
        }

        publishClickEvent(toResponse(url), request);
        return url.getLongUrl();
    }

    // ─── PHASE 2: Manage URLs ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UrlResponse getById(UUID id, AuthenticatedPrincipal principal) {
        Url url = findOwnedUrl(id, principal);
        return toResponse(url);
    }

    @Transactional(readOnly = true)
    public PagedResponse<UrlResponse> listForUser(UUID ownerId, int page, int size, AuthenticatedPrincipal principal) {
        assertOwnerOrAdmin(ownerId, principal);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return PagedResponse.from(
                urlRepository.findByOwnerIdAndDeletedAtIsNull(ownerId, pageable).map(this::toResponse)
        );
    }

    @Transactional
    public UrlResponse update(UUID id, UpdateUrlRequest request, AuthenticatedPrincipal principal, String ipAddress) {
        Url url = findOwnedUrl(id, principal);

        if (request.longUrl() != null) {
            url.setLongUrl(urlValidator.validateAndNormalize(request.longUrl()));
            url.setLongUrlHash(HashUtil.sha256Hex(url.getLongUrl()));
        }
        if (request.expiresAt() != null) {
            url.setExpiresAt(request.expiresAt());
            if (url.getStatus() == UrlStatus.EXPIRED) {
                url.setStatus(UrlStatus.ACTIVE);
            }
        }
        if (request.maxClicks() != null) {
            url.setMaxClicks(request.maxClicks());
        }
        if (request.visibility() != null) {
            url.setVisibility(request.visibility());
        }
        if (request.password() != null && !request.password().isBlank()) {
            url.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (Boolean.TRUE.equals(request.removePassword())) {
            url.setPasswordHash(null);
        }

        url = urlRepository.save(url);
        cacheService.evict(url.getShortCode());

        auditLogService.log(principal.id(), AuditAction.URL_UPDATED, "Url", id.toString(),
                ipAddress, Map.of("shortCode", url.getShortCode()));

        UrlResponse response = toResponse(url);
        cacheService.put(url.getShortCode(), response, Duration.ofSeconds(urlCacheTtlSeconds));
        return response;
    }

    @Transactional
    public void disable(UUID id, AuthenticatedPrincipal principal, String ipAddress) {
        Url url = findOwnedUrl(id, principal);
        urlRepository.updateStatus(id, UrlStatus.DISABLED);
        cacheService.evict(url.getShortCode());
        auditLogService.log(principal.id(), AuditAction.URL_DISABLED, "Url", id.toString(), ipAddress, Map.of());
    }

    @Transactional
    public void enable(UUID id, AuthenticatedPrincipal principal, String ipAddress) {
        Url url = findOwnedUrl(id, principal);
        urlRepository.updateStatus(id, UrlStatus.ACTIVE);
        cacheService.evict(url.getShortCode());
        auditLogService.log(principal.id(), AuditAction.URL_ENABLED, "Url", id.toString(), ipAddress, Map.of());
    }

    @Transactional
    public void softDelete(UUID id, AuthenticatedPrincipal principal, String ipAddress) {
        Url url = findOwnedUrl(id, principal);
        urlRepository.softDelete(id, Instant.now());
        cacheService.evict(url.getShortCode());
        auditLogService.log(principal.id(), AuditAction.URL_SOFT_DELETED, "Url", id.toString(), ipAddress, Map.of());
    }

    /** Admin only: immediately hard-delete (no recovery). */
    @Transactional
    public void hardDelete(UUID id, AuthenticatedPrincipal principal, String ipAddress) {
        assertAdmin(principal);
        Url url = urlRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("URL not found: " + id));
        urlRepository.delete(url);
        cacheService.evict(url.getShortCode());
        auditLogService.log(principal.id(), AuditAction.URL_HARD_DELETED, "Url", id.toString(), ipAddress, Map.of());
    }

    @Transactional
    public List<UrlResponse> bulkCreate(BulkCreateUrlRequest request, AuthenticatedPrincipal principal, String ipAddress) {
        List<UrlResponse> results = new ArrayList<>();
        for (var item : request.items()) {
            results.add(create(item, principal, ipAddress));
        }
        return results;
    }

    // ─── Scheduled jobs ──────────────────────────────────────────────────────

    /**
     * Marks expired ACTIVE URLs as EXPIRED and evicts their cache entries — covers both
     * time-based expiry (expiresAt passed) and click-count-based expiry (clickCount reached
     * maxClicks; see UrlRepository#findExpiredButStillActive). Runs every 5 minutes.
     * Processes in pages of 500 to bound memory footprint and avoid a single transaction
     * holding locks on thousands of rows.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void markExpiredUrls() {
        Pageable page = PageRequest.of(0, 500);
        List<Url> expired = urlRepository.findExpiredButStillActive(Instant.now(), page);
        if (!expired.isEmpty()) {
            log.info("Expiry sweep: marking {} URLs as EXPIRED (time-based or max-clicks-reached)", expired.size());
            for (Url url : expired) {
                url.setStatus(UrlStatus.EXPIRED);
                cacheService.evict(url.getShortCode());
            }
            urlRepository.saveAll(expired);
        }
    }

    /**
     * Hard-deletes soft-deleted rows older than 30 days — the retention window giving
     * owners time to un-delete accidentally deleted links via customer support.
     * Runs daily at 02:00 UTC (cron syntax in UTC, consistent across any host timezone).
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void hardDeleteExpiredSoftDeletes() {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(30));
        List<UUID> toDelete = urlRepository.findSoftDeletedBefore(cutoff);
        if (!toDelete.isEmpty()) {
            log.info("Hard-delete sweep: permanently removing {} soft-deleted URLs older than 30 days", toDelete.size());
            urlRepository.hardDeleteByIds(toDelete);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Url findOwnedUrl(UUID id, AuthenticatedPrincipal principal) {
        Url url = urlRepository.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("URL not found: " + id));
        boolean isAdmin = isAdmin(principal);
        boolean isOwner = url.getOwner() != null && url.getOwner().getId().equals(principal.id());
        if (!isOwner && !isAdmin) {
            throw new UnauthorizedActionException("You do not own this link.");
        }
        return url;
    }

    private void assertOwnerOrAdmin(UUID ownerId, AuthenticatedPrincipal principal) {
        if (!ownerId.equals(principal.id()) && !isAdmin(principal)) {
            throw new UnauthorizedActionException("Access denied.");
        }
    }

    private void assertAdmin(AuthenticatedPrincipal principal) {
        if (!isAdmin(principal)) {
            throw new UnauthorizedActionException("Admin privileges required.");
        }
    }

    private boolean isAdmin(AuthenticatedPrincipal principal) {
        // Roles are encoded in the JWT claims and surfaced via Spring Security authorities.
        // We check the SecurityContext here rather than hitting the DB.
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private void publishClickEvent(UrlResponse url, HttpServletRequest request) {
        try {
            String clientIp = IpAddressUtil.resolveClientIp(request);
            String ipHash = HashUtil.sha256Hex(clientIp);
             GeoLocationService.GeoLocation geo =
             new GeoLocationService.GeoLocation("XX", "Unknown");

            clickEventProducer.publish(new ClickEventMessage(
                    url.id(),
                    url.shortCode(),
                    Instant.now(),
                    ipHash,
                    request.getHeader("User-Agent"),
                    request.getHeader("Referer"),
                    geo.countryCode(),
                    geo.city()
            ));
        } catch (Exception e) {
            // Never let click-event publishing fail a redirect.
            log.error("Failed to publish click event for shortCode={}", url.shortCode(), e);
        }
    }

    public UrlResponse toResponse(Url url) {
        String domainPart = baseUrl;
        return new UrlResponse(
                url.getId().toString(),
                url.getShortCode(),
                domainPart + "/r/" + url.getShortCode(),
                url.getLongUrl(),
                url.getStatus(),
                url.getVisibility(),
                url.isCustomAlias(),
                url.isPasswordProtected(),
                url.getExpiresAt(),
                url.getMaxClicks(),
                url.getClickCount(),
                url.getCreatedAt(),
                url.getUpdatedAt()
        );
    }
}