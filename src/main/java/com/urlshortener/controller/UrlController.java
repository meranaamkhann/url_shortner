package com.urlshortener.controller;

import com.urlshortener.dto.request.BulkCreateUrlRequest;
import com.urlshortener.dto.request.CreateUrlRequest;
import com.urlshortener.dto.request.UpdateUrlRequest;
import com.urlshortener.dto.response.PagedResponse;
import com.urlshortener.dto.response.QrCodeResponse;
import com.urlshortener.dto.response.UrlResponse;
import com.urlshortener.security.AuthenticatedPrincipal;
import com.urlshortener.service.QrCodeService;
import com.urlshortener.service.impl.UrlService;
import com.urlshortener.util.IpAddressUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
@Tag(name = "URL Management", description = "Create, update, list, disable, and delete short URLs")
public class UrlController {

    private final UrlService urlService;
    private final QrCodeService qrCodeService;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Shortening works for both authenticated and anonymous users (Functional Requirement:
     * anonymous shortening is a common Bitly/TinyURL pattern for top-of-funnel growth).
     * When authenticated, the link is associated with the caller as owner; otherwise it's
     * an ownerless public link manageable only via its returned ID for the session.
     */
    @PostMapping
    @Operation(summary = "Shorten a URL (works authenticated or anonymous)")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request,
                                               Authentication authentication,
                                               HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        UrlResponse response = urlService.create(request, principal, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/bulk")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Bulk-create up to 100 short URLs in a single request")
    public ResponseEntity<List<UrlResponse>> bulkCreate(@Valid @RequestBody BulkCreateUrlRequest request,
                                                          Authentication authentication,
                                                          HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        List<UrlResponse> results = urlService.bulkCreate(request, principal, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a single URL by its internal ID (owner or admin only)")
    public ResponseEntity<UrlResponse> getById(@PathVariable UUID id, Authentication authentication) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        return ResponseEntity.ok(urlService.getById(id, principal));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the current user's URLs, paginated")
    public ResponseEntity<PagedResponse<UrlResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        return ResponseEntity.ok(urlService.listForUser(principal.id(), page, Math.min(size, 100), principal));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Partially update a URL's destination, expiry, visibility, or password")
    public ResponseEntity<UrlResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateUrlRequest request,
                                               Authentication authentication, HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        return ResponseEntity.ok(urlService.update(id, request, principal, IpAddressUtil.resolveClientIp(httpRequest)));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Disable a URL (redirects will return 410 Gone until re-enabled)")
    public ResponseEntity<Void> disable(@PathVariable UUID id, Authentication authentication, HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        urlService.disable(id, principal, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Re-enable a previously disabled URL")
    public ResponseEntity<Void> enable(@PathVariable UUID id, Authentication authentication, HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        urlService.enable(id, principal, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Soft-delete a URL (recoverable for 30 days)")
    public ResponseEntity<Void> softDelete(@PathVariable UUID id, Authentication authentication, HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        urlService.softDelete(id, principal, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/permanent")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Permanently and immediately delete a URL (admin only, irreversible)")
    public ResponseEntity<Void> hardDelete(@PathVariable UUID id, Authentication authentication, HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        urlService.hardDelete(id, principal, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the QR code for a URL as a raw PNG image")
    public ResponseEntity<byte[]> qrCodePng(@PathVariable UUID id, Authentication authentication,
                                             @RequestParam(defaultValue = "300") int size) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        UrlResponse url = urlService.getById(id, principal);
        byte[] png = qrCodeService.generatePng(url.shortUrl(), Math.min(Math.max(size, 100), 1000));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping("/{id}/qrcode/base64")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the QR code for a URL as a base64-encoded PNG (convenient for embedding in JSON/HTML)")
    public ResponseEntity<QrCodeResponse> qrCodeBase64(@PathVariable UUID id, Authentication authentication) {
        AuthenticatedPrincipal principal = extractPrincipal(authentication);
        UrlResponse url = urlService.getById(id, principal);
        String base64 = qrCodeService.generateBase64Png(url.shortUrl());
        return ResponseEntity.ok(new QrCodeResponse(url.shortUrl(), base64));
    }

    private AuthenticatedPrincipal extractPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal p)) {
            return null; // anonymous caller — allowed for URL creation
        }
        return p;
    }
}
