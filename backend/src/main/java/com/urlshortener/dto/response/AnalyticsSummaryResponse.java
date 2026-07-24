package com.urlshortener.dto.response;

import java.util.List;
import java.util.Map;

public record AnalyticsSummaryResponse(
        String shortCode,
        long totalClicks,
        long uniqueClicks,
        long botClicks,
        Map<String, Long> clicksByCountry,
        Map<String, Long> clicksByDevice,
        List<TopReferrer> topReferrers,
        List<DailyClicks> last30Days
) {
    public record TopReferrer(String referrer, long count) {
    }

    public record DailyClicks(String day, long totalClicks, long uniqueClicks) {
    }
}
