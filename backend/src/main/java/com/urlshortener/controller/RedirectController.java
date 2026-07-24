package com.urlshortener.controller;

import com.urlshortener.dto.request.UrlAccessRequest;
import com.urlshortener.service.impl.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single highest-traffic endpoint in the entire system. Deliberately kept on its
 * own un-prefixed "/r/{code}" path (rather than under /api/v1/...) so it reads as a
 * clean, short, shareable URL: https://short.ly/r/aB3xK9q.
 *
 * This controller is intentionally thin — all caching, validation, and click-tracking
 * logic lives in UrlService#resolveAndTrack so it's independently unit-testable and so
 * the controller's only job is the HTTP-specific concern of issuing a redirect.
 *
 * 301 vs 302: we use 302 (temporary redirect) by default rather than 301 (permanent),
 * even though most short links never change. This is deliberate: a 301 is aggressively
 * cached by browsers — once a browser caches a 301 for a given short code, it will keep
 * redirecting locally WITHOUT ever hitting our server again, which would (a) make
 * click-tracking silently undercount and (b) make link disabling/expiration invisible
 * to that browser. 302 trades a marginal amount of redirect performance for accurate
 * analytics and enforceable link lifecycle — the right trade-off for this product.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Redirect", description = "Public short-link resolution (the hot path)")
public class RedirectController {

    private final UrlService urlService;

    @GetMapping("/r/{shortCode}")
    @Operation(summary = "Resolve a short code and redirect to its destination URL")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        String longUrl = urlService.resolveAndTrack(shortCode, request);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, longUrl)
                .header(HttpHeaders.CACHE_CONTROL, "no-store") // see class javadoc: never let browsers cache this
                .build();
    }

    @PostMapping("/r/{shortCode}/access")
    @Operation(summary = "Unlock a password-protected short link and redirect on success")
    public ResponseEntity<Void> redirectWithPassword(@PathVariable String shortCode,
                                                       @Valid @RequestBody UrlAccessRequest request,
                                                       HttpServletRequest httpRequest) {
        String longUrl = urlService.resolveWithPassword(shortCode, request.password(), httpRequest);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, longUrl)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
