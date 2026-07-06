package com.urlshortener.dto.request;

import com.urlshortener.domain.enums.Visibility;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** All fields optional/nullable: PATCH-style partial update. */
public record UpdateUrlRequest(

        @Size(max = 2048)
        String longUrl,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt,

        @Positive
        Long maxClicks,

        Visibility visibility,

        @Size(max = 128)
        String password,

        Boolean removePassword
) {
}
