package com.urlshortener.util;

import com.urlshortener.domain.enums.DeviceType;

import java.util.regex.Pattern;

/**
 * A deliberately lightweight User-Agent parser. A production system would typically use
 * a maintained library (ua-parser) or an edge service, but rolling a small one here keeps
 * the project dependency-light and is more than enough to demonstrate device/bot
 * classification for analytics and bot-traffic filtering.
 */
public final class UserAgentParser {

    private static final Pattern BOT_PATTERN = Pattern.compile(
            "(?i)(bot|crawl|spider|slurp|curl|wget|python-requests|headlesschrome|monitor|pingdom|uptime)");
    private static final Pattern TABLET_PATTERN = Pattern.compile("(?i)(ipad|tablet)");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?i)(mobi|iphone|android)");

    private UserAgentParser() {
    }

    public record ParsedUserAgent(DeviceType deviceType, String browser, String os, boolean bot) {
    }

    public static ParsedUserAgent parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new ParsedUserAgent(DeviceType.UNKNOWN, "Unknown", "Unknown", false);
        }
        boolean isBot = BOT_PATTERN.matcher(userAgent).find();
        DeviceType deviceType;
        if (isBot) {
            deviceType = DeviceType.BOT;
        } else if (TABLET_PATTERN.matcher(userAgent).find()) {
            deviceType = DeviceType.TABLET;
        } else if (MOBILE_PATTERN.matcher(userAgent).find()) {
            deviceType = DeviceType.MOBILE;
        } else {
            deviceType = DeviceType.DESKTOP;
        }

        String browser = extract(userAgent,
                "Edg", "Edge",
                "Chrome", "Chrome",
                "Firefox", "Firefox",
                "Safari", "Safari",
                "OPR", "Opera");

        String os = extract(userAgent,
                "Windows", "Windows",
                "Mac OS X", "macOS",
                "Android", "Android",
                "iPhone OS", "iOS",
                "Linux", "Linux");

        return new ParsedUserAgent(deviceType, browser, os, isBot);
    }

    private static String extract(String ua, String... tokenLabelPairs) {
        for (int i = 0; i < tokenLabelPairs.length; i += 2) {
            if (ua.contains(tokenLabelPairs[i])) {
                return tokenLabelPairs[i + 1];
            }
        }
        return "Other";
    }
}
