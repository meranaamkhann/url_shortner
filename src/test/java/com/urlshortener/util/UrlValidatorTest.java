package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.exception.MaliciousUrlException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator("http://localhost:8080");

    @Test
    @DisplayName("Valid http/https URLs are accepted and trimmed")
    void acceptsValidUrls() {
        assertThat(validator.validateAndNormalize("  https://example.com/path?x=1  "))
                .isEqualTo("https://example.com/path?x=1");
    }

    @Test
    @DisplayName("Blank or null URLs are rejected")
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> validator.validateAndNormalize(""))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize(null))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("URLs exceeding the max length are rejected")
    void rejectsTooLongUrl() {
        String longUrl = "https://example.com/" + "a".repeat(2100);
        assertThatThrownBy(() -> validator.validateAndNormalize(longUrl))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "file:///etc/passwd"
    })
    @DisplayName("Disallowed/dangerous schemes are rejected")
    void rejectsDangerousSchemes(String maliciousUrl) {
        assertThatThrownBy(() -> validator.validateAndNormalize(maliciousUrl))
                .isInstanceOfAny(InvalidUrlException.class, MaliciousUrlException.class);
    }

    @Test
    @DisplayName("Self-referential URLs are rejected to prevent redirect loops")
    void rejectsSelfReferentialUrl() {
        assertThatThrownBy(() -> validator.validateAndNormalize("http://localhost:8080/r/abc123"))
                .isInstanceOf(MaliciousUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bit.ly", "tinyurl.com", "t.co", "is.gd", "ow.ly"})
    @DisplayName("Known shortener domains are rejected (prevents shortener-chaining abuse)")
    void rejectsKnownShortenerDomains(String domain) {
        assertThatThrownBy(() -> validator.validateAndNormalize("https://" + domain + "/abc"))
                .isInstanceOf(MaliciousUrlException.class);
    }

    @Test
    @DisplayName("URLs without a host are rejected")
    void rejectsMissingHost() {
        assertThatThrownBy(() -> validator.validateAndNormalize("https:///path"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("isSafeForServerSideFetch rejects loopback addresses (SSRF protection)")
    void rejectsLoopbackForServerSideFetch() {
        assertThat(validator.isSafeForServerSideFetch("http://127.0.0.1/admin")).isFalse();
        assertThat(validator.isSafeForServerSideFetch("http://localhost/admin")).isFalse();
    }

    @Test
    @DisplayName("isSafeForServerSideFetch allows public addresses")
    void allowsPublicAddressForServerSideFetch() {
        assertThat(validator.isSafeForServerSideFetch("https://example.com")).isTrue();
    }
}
