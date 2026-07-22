package com.urlshortener.dto.response;

import java.time.Instant;
import java.util.Set;

public record UserProfileResponse(
        String id,
        String username,
        String email,
        String fullName,
        Set<String> roles,
        Instant lastLoginAt,
        Instant createdAt
) {
}
