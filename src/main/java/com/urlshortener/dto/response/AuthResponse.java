package com.urlshortener.dto.response;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserSummary user
) {
    public record UserSummary(String id, String username, String email, java.util.Set<String> roles) {
    }
}
