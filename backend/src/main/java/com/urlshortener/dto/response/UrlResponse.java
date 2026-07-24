package com.urlshortener.dto.response;

import com.urlshortener.domain.enums.UrlStatus;
import com.urlshortener.domain.enums.Visibility;

import java.time.Instant;

public record UrlResponse(
        String id,
        String shortCode,
        String shortUrl,
        String longUrl,
        UrlStatus status,
        Visibility visibility,
        boolean customAlias,
        boolean passwordProtected,
        Instant expiresAt,
        Long maxClicks,
        long clickCount,
        Instant createdAt,
        Instant updatedAt
) {
}
