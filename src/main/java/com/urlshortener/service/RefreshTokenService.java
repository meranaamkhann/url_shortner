package com.urlshortener.service;

import com.urlshortener.domain.entity.RefreshToken;
import com.urlshortener.domain.entity.User;
import com.urlshortener.exception.InvalidTokenException;
import com.urlshortener.repository.RefreshTokenRepository;
import com.urlshortener.security.JwtTokenProvider;
import com.urlshortener.util.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages refresh tokens with rotation: every time a refresh token is used, it is
 * revoked and a brand-new one is issued in its place (the old one's `replaced_by`
 * points to the new one). This means a stolen refresh token can only be replayed
 * ONCE before the legitimate user's next refresh invalidates it — if an attacker
 * and the legitimate user both try to use the same (stolen) token, whichever uses
 * it second gets an InvalidTokenException, which is itself a strong signal of token
 * theft worth alerting on.
 *
 * Only the SHA-256 hash of the raw refresh token is ever persisted — if the database
 * is ever compromised, the leaked hashes cannot be replayed without inverting SHA-256.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public String issue(User user, String userAgent, String ipAddress) {
        String rawToken = jwtTokenProvider.generateRefreshTokenRaw(user.getId());
        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .tokenHash(HashUtil.sha256Hex(rawToken))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpirationMs()))
                .revoked(false)
                .userAgent(userAgent)
                .ipAddress(ipAddress)
                .build();
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    /** Validates the presented raw token, revokes it, and issues a replacement (rotation). */
    @Transactional
    public RotationResult rotate(String rawToken, String userAgent, String ipAddress) {
        jwtTokenProvider.parseClaims(rawToken); // throws InvalidTokenException if malformed/expired
        String tokenHash = HashUtil.sha256Hex(rawToken);

        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognized."));

        if (!existing.isActive()) {
            throw new InvalidTokenException("Refresh token has been revoked or has expired. Please log in again.");
        }

        existing.setRevoked(true);
        User user = existing.getUser();
        String newRawToken = issue(user, userAgent, ipAddress);

        // Link old -> new for forensic traceability of the rotation chain.
        refreshTokenRepository.findByTokenHash(HashUtil.sha256Hex(newRawToken))
                .ifPresent(newEntity -> existing.setReplacedBy(newEntity.getId()));
        refreshTokenRepository.save(existing);

        return new RotationResult(user, newRawToken);
    }

    @Transactional
    public void revokeAllForUser(java.util.UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }

    public record RotationResult(User user, String newRefreshToken) {
    }
}
