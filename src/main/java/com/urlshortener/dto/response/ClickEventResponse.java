package com.urlshortener.dto.response;

import java.time.Instant;

public record ClickEventResponse(
        Instant clickedAt,
        String countryCode,
        String city,
        String referrer,
        String deviceType,
        String browser,
        String os,
        boolean bot
) {
}
