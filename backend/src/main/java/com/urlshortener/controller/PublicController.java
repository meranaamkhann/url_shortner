package com.urlshortener.controller;

import com.urlshortener.dto.response.LinkPreviewResponse;
import com.urlshortener.service.LinkPreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Validated
@Tag(name = "Public", description = "Unauthenticated, publicly accessible endpoints")
public class PublicController {

    private final LinkPreviewService linkPreviewService;

    @GetMapping("/link-preview")
    @Operation(summary = "Fetch OpenGraph metadata (title/description/image) for a destination URL")
    public ResponseEntity<LinkPreviewResponse> preview(@RequestParam @NotBlank String url) {
        return ResponseEntity.ok(linkPreviewService.fetchPreview(url));
    }
}
