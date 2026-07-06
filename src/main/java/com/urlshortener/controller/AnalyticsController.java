package com.urlshortener.controller;

import com.urlshortener.dto.response.AnalyticsSummaryResponse;
import com.urlshortener.dto.response.ClickEventResponse;
import com.urlshortener.dto.response.PagedResponse;
import com.urlshortener.security.AuthenticatedPrincipal;
import com.urlshortener.service.impl.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/urls/{id}/analytics")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Analytics", description = "Click analytics and event history for a URL")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/summary")
    @Operation(summary = "Aggregated click summary: totals, by-country, by-device, top referrers, 30-day trend")
    public ResponseEntity<AnalyticsSummaryResponse> summary(@PathVariable UUID id, Authentication authentication) {
        var principal = (AuthenticatedPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(analyticsService.getSummary(id, principal));
    }

    @GetMapping("/events")
    @Operation(summary = "Paginated raw click event log for a URL")
    public ResponseEntity<PagedResponse<ClickEventResponse>> events(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        var principal = (AuthenticatedPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(analyticsService.getRecentClicks(id, page, Math.min(size, 100), principal));
    }
}
