package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;

import java.util.Set;
import java.util.regex.Pattern;

public final class AliasValidator {

    private static final Pattern VALID_ALIAS = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");

    /** Reserved path segments that would otherwise collide with real API routes. */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "api", "admin", "auth", "login", "logout", "register", "health", "metrics",
            "actuator", "swagger-ui", "docs", "static", "favicon.ico", "robots.txt",
            "v1", "v2", "www", "app", "dashboard", "settings", "null", "undefined"
    );

    private AliasValidator() {
    }

    public static void validate(String alias) {
        if (alias == null || !VALID_ALIAS.matcher(alias).matches()) {
            throw new InvalidUrlException(
                    "Custom alias must be 3-32 characters and contain only letters, digits, hyphens, or underscores.");
        }
        if (RESERVED_WORDS.contains(alias.toLowerCase())) {
            throw new InvalidUrlException("'" + alias + "' is a reserved word and cannot be used as an alias.");
        }
    }
}
