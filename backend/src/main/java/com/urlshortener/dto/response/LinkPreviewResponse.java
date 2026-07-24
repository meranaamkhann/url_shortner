package com.urlshortener.dto.response;

public record LinkPreviewResponse(
        String url,
        String title,
        String description,
        String imageUrl,
        String siteName
) {
}
