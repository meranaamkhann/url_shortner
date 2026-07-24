package com.urlshortener.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Used when a viewer must supply a password to unlock a password-protected link. */
public record UrlAccessRequest(
        @NotBlank
        String password
) {
}
