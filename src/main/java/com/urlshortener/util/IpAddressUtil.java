package com.urlshortener.util;

import jakarta.servlet.http.HttpServletRequest;

public final class IpAddressUtil {

    private IpAddressUtil() {
    }

    /**
     * Resolves the real client IP, accounting for the fact that traffic arrives through
     * a load balancer / ingress (so request.getRemoteAddr() would otherwise always return
     * the LB's internal IP). X-Forwarded-For can contain a chain ("client, proxy1, proxy2");
     * the left-most entry is the original client as set by our own trusted edge proxy.
     *
     * Security note: this header is only trustworthy because our ingress/load balancer
     * strips and re-sets it for inbound traffic — never trust X-Forwarded-For from a
     * client that can reach the app directly.
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
