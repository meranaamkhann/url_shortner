package com.urlshortener.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {

    private HashUtil() {
    }

    /**
     * SHA-256 hex digest. Used for:
     *  - long_url_hash: enables O(1) "have we already shortened this URL?" lookups
     *    on a TEXT column without indexing the full URL body.
     *  - ip_hash: stores click-analytics IP data without ever persisting raw IPs
     *    (privacy-by-design / GDPR data minimisation).
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
