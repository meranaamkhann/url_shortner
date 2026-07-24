package com.urlshortener.service;

import org.springframework.stereotype.Service;

/**
 * Resolves a client IP to a coarse geographic location for analytics.
 *
 * STUBBED IMPLEMENTATION: a real deployment would embed the MaxMind GeoLite2 (or
 * commercial GeoIP2) binary database and query it locally — this is a fully offline,
 * sub-millisecond lookup with no external API call on the hot path, which is why it's
 * the standard industry choice over hitting a geo-IP HTTP API per request. The binary
 * .mmdb file isn't bundled in this project (it's ~70MB and requires a MaxMind license
 * to redistribute), so this stub returns a placeholder so the rest of the analytics
 * pipeline (storage, aggregation, dashboards) is fully wired up and demonstrable.
 *
 * Swapping in the real thing means: add com.maxmind.geoip2:geoip2 dependency, load the
 * .mmdb file as a singleton DatabaseReader bean, and replace the body of resolve() with
 * a reader.country(ip) / reader.city(ip) call. No other code in the system changes.
 */
@Service
public class GeoLocationService {

    public record GeoLocation(String countryCode, String city) {
    }

    public GeoLocation resolve(String ipAddress) {
        if (ipAddress == null || isPrivateOrLoopback(ipAddress)) {
            return new GeoLocation("XX", "Unknown");
        }
        // Placeholder: production code replaces this with a MaxMind DatabaseReader lookup.
        return new GeoLocation("XX", "Unknown");
    }

    private boolean isPrivateOrLoopback(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1");
    }
}
