package com.urlshortener.service;

import com.urlshortener.dto.response.LinkPreviewResponse;
import com.urlshortener.exception.MaliciousUrlException;
import com.urlshortener.util.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Fetches OpenGraph/meta tags from the destination URL to power link previews
 * (Functional Requirement: Link preview).
 *
 * This is the ONE place the server makes an outbound request to a user-supplied URL,
 * which makes it the system's primary SSRF surface — handled by:
 *   1. UrlValidator#isSafeForServerSideFetch resolving the host and rejecting loopback/
 *      private/link-local addresses (blocks attacks like fetching http://169.254.169.254/
 *      to read cloud instance metadata through our server).
 *   2. A strict connect/read timeout so a slow/malicious endpoint can't tie up a request
 *      thread indefinitely.
 *   3. A capped response size (via Jsoup's maxBodySize) so a multi-gigabyte response
 *      can't be used for a memory-exhaustion DoS.
 * Results are cached (24h TTL) since destination page metadata rarely changes and this
 * avoids re-fetching the same third-party page on every preview request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkPreviewService {

    private static final int TIMEOUT_MS = 4000;
    private static final int MAX_BODY_SIZE_BYTES = 2 * 1024 * 1024; // 2MB cap

    private final UrlValidator urlValidator;

    @Cacheable(value = "linkPreview", key = "#url")
    public LinkPreviewResponse fetchPreview(String url) {
        if (!urlValidator.isSafeForServerSideFetch(url)) {
            throw new MaliciousUrlException("This URL cannot be previewed (resolves to a disallowed network address).");
        }
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(MAX_BODY_SIZE_BYTES)
                    .userAgent("Mozilla/5.0 (compatible; URLShortenerBot/1.0; +https://yourdomain.com/bot)")
                    .followRedirects(true)
                    .get();

            String title = firstNonBlank(meta(doc, "og:title"), doc.title());
            String description = firstNonBlank(meta(doc, "og:description"), meta(doc, "description"));
            String image = meta(doc, "og:image");
            String siteName = meta(doc, "og:site_name");

            return new LinkPreviewResponse(url, title, description, image, siteName);
        } catch (Exception e) {
            log.warn("Failed to fetch link preview for {}: {}", url, e.getMessage());
            return new LinkPreviewResponse(url, null, null, null, null);
        }
    }

    private String meta(Document doc, String property) {
        var el = doc.selectFirst("meta[property=" + property + "], meta[name=" + property + "]");
        return el != null ? el.attr("content") : null;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
