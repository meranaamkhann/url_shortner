package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.exception.MaliciousUrlException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and sanitizes user-submitted long URLs before they are ever persisted.
 *
 * This is the system's single most important security boundary: every "malicious
 * URL", "redirect loop", and "SSRF via link-preview fetch" edge case funnels through
 * here. Keeping it as one well-tested component (rather than scattering checks across
 * controllers/services) is a deliberate single-responsibility design choice.
 */
@Component
public class UrlValidator {

    private static final int MAX_URL_LENGTH = 2048;

    /** Only http/https are shortenable. javascript:, data:, file:, vbscript: etc. are XSS/RCE vectors. */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * A minimal local blocklist of known malicious-pattern indicators. In production this
     * would be backed by a real threat-intel feed (Google Safe Browsing API, PhishTank,
     * an internal Kafka-fed blocklist refreshed every few minutes) — see ThreatIntelClient
     * interface discussed in docs/ARCHITECTURE.md. Kept local here so the project is
     * fully runnable without an external API key.
     */
    private static final Pattern SUSPICIOUS_PATTERN = Pattern.compile(
            "(?i)(javascript:|data:text/html|vbscript:|<script|onerror=|onload=)");

    private final String ownDomain;
    private final Set<String> blockedShortenerDomains;

    public UrlValidator(@Value("${app.base-url}") String baseUrl) {
        this.ownDomain = URI.create(baseUrl).getHost();
        // Prevents "shorten a shortened link" chains that can be abused to build redirect loops
        // or to obscure the final destination through several hops of third-party shorteners.
        this.blockedShortenerDomains = Set.of("bit.ly", "tinyurl.com", "t.co", "is.gd", "ow.ly");
    }

    /**
     * @throws InvalidUrlException   if the URL is malformed, too long, or uses a disallowed scheme
     * @throws MaliciousUrlException if the URL matches a known-malicious pattern or would create a redirect loop
     */
    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidUrlException("URL must not be empty.");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("URL exceeds the maximum allowed length of " + MAX_URL_LENGTH + " characters.");
        }
        if (SUSPICIOUS_PATTERN.matcher(trimmed).find()) {
            throw new MaliciousUrlException("This URL contains a disallowed pattern and was rejected.");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("The provided URL is not syntactically valid.");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new InvalidUrlException("Only http:// and https:// URLs may be shortened.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("The URL must include a valid host.");
        }

        // IDN/punycode normalisation guards against homograph-style host spoofing
        // (e.g. a Cyrillic 'а' that visually matches Latin 'a' in "paypal.com").
        String normalizedHost = IDN.toASCII(host).toLowerCase();

        if (normalizedHost.equals(ownDomain)) {
            throw new MaliciousUrlException("Shortening a link that points back to this service is not allowed (redirect loop).");
        }
        if (blockedShortenerDomains.contains(normalizedHost)) {
            throw new MaliciousUrlException("Shortening links from other URL shorteners is not allowed.");
        }

        return trimmed;
    }

    /**
     * Used only by the link-preview/metadata fetcher (the one place the server itself makes
     * an outbound HTTP request on the user's behalf). Resolves the host and rejects loopback,
     * link-local, and private (RFC1918) addresses to prevent SSRF against internal infrastructure
     * (e.g. a malicious user submitting http://169.254.169.254/ to probe a cloud metadata endpoint).
     */
    public boolean isSafeForServerSideFetch(String url) {
        try {
            URI uri = new URI(url);
            InetAddress addr = InetAddress.getByName(uri.getHost());
            return !(addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress()
                    || addr.isMulticastAddress());
        } catch (URISyntaxException | UnknownHostException e) {
            return false;
        }
    }
}
