package com.urlshortener.dto.response;

import java.time.Instant;
import java.util.Set;

public record UserResponse(
        String id,
        String email,
        String username,
        String fullName,
        boolean enabled,
        Set<String> roles,
        Instant createdAt
) {
}
