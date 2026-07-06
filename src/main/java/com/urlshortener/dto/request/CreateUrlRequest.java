package com.urlshortener.dto.request;

import com.urlshortener.domain.enums.Visibility;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateUrlRequest(

        @NotBlank(message = "longUrl is required")
        @Size(max = 2048, message = "longUrl must not exceed 2048 characters")
        String longUrl,

        @Size(min = 3, max = 32, message = "customAlias must be between 3 and 32 characters")
        String customAlias,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt,

        @Positive(message = "maxClicks must be a positive number")
        Long maxClicks,

        Visibility visibility,

        @Size(max = 128, message = "password must not exceed 128 characters")
        String password,

        /** Optional - id of a verified custom domain owned by the caller. Null = default domain. */
        String customDomainId
) {
}
