package com.urlshortener.security;

import com.urlshortener.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String TEST_SECRET =
            "dGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS1mb3ItdW5pdC10ZXN0aW5nLW9ubHktbm90LWZvci1wcm9k";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(TEST_SECRET, 900_000L, 604_800_000L);
    }

    @Test
    void generatesAndParsesAccessTokenRoundTrip() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "alice", List.of("ROLE_USER"));

        Claims claims = provider.parseClaims(token);

        assertThat(provider.getUserId(claims)).isEqualTo(userId);
        assertThat(provider.getUsername(claims)).isEqualTo("alice");
        assertThat(provider.getRoles(claims)).containsExactly("ROLE_USER");
        assertThat(provider.isAccessToken(claims)).isTrue();
    }

    @Test
    void refreshTokenIsMarkedAsNonAccessType() {
        UUID userId = UUID.randomUUID();
        String refreshToken = provider.generateRefreshTokenRaw(userId);

        Claims claims = provider.parseClaims(refreshToken);

        assertThat(provider.isAccessToken(claims)).isFalse();
        assertThat(provider.getUserId(claims)).isEqualTo(userId);
    }

    @Test
    void rejectsTamperedToken() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "alice", List.of("ROLE_USER"));
        String tampered = token.substring(0, token.length() - 5) + "AAAAA";

        assertThatThrownBy(() -> provider.parseClaims(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtTokenProvider otherProvider = new JwtTokenProvider(
                "ZGlmZmVyZW50LXNlY3JldC1rZXktdGhhdC1pcy1hbHNvLWxvbmctZW5vdWdoLWZvci1obWFjLXNoYTI1Ng==",
                900_000L, 604_800_000L);
        String token = otherProvider.generateAccessToken(UUID.randomUUID(), "bob", List.of("ROLE_USER"));

        assertThatThrownBy(() -> provider.parseClaims(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> provider.parseClaims("not.a.jwt"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
