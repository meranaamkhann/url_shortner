package com.urlshortener.service;

import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Short code generation strategy.
 *
 * CHOSEN APPROACH: random Base62 generation + DB-unique-constraint collision retry.
 *   1. Generate a random N-character Base62 string (default N=7 -> 62^7 ≈ 3.5 trillion
 *      keyspace).
 *   2. Attempt the insert. If the unique index on (domain, short_code) rejects it
 *      (DataIntegrityViolationException), regenerate and retry, up to a small bound.
 *
 * Why this over alternatives (discussed further in docs/ARCHITECTURE.md and the
 * interview Q&A doc):
 *   - Auto-increment counter + Base62 encoding (classic "TinyURL" approach): simple and
 *     collision-free by construction, but a single global counter is a write bottleneck
 *     and a single point of contention once you horizontally scale write traffic across
 *     many app instances/regions — the exact opposite of what we want for "billions of
 *     URLs / many app instances" — without a separate distributed ID allocator (Snowflake,
 *     Twitter-style, or a pre-allocated range/ticket service per app instance).
 *   - Pre-generated Key Generation Service (KGS): a dedicated service pre-computes and
 *     stores unused random keys in batches, and hands each app instance a block of, say,
 *     1,000 keys to consume locally with zero per-request coordination. This is what
 *     Bitly-scale systems actually do, and is the natural "Phase 5" evolution of this
 *     design if/when collision-retry overhead becomes measurable at extreme write QPS.
 *   - Random + retry (what's implemented here): no extra moving parts, no separate
 *     service to run/scale/monitor, and at 7+ characters the collision probability is
 *     astronomically low (birthday-paradox math: even at 1 billion existing codes, the
 *     chance any single new random code collides is roughly 1B / 3.5T ≈ 0.03%), so the
 *     retry loop almost never actually loops. This is the pragmatic choice for a project
 *     of this scope, with the KGS noted as the scale-out path.
 */
@Slf4j
@Service
public class ShortCodeGeneratorService {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int codeLength;
    private final UrlRepository urlRepository;

    public ShortCodeGeneratorService(@Value("${app.short-code.length:7}") int codeLength, UrlRepository urlRepository) {
        this.codeLength = codeLength;
        this.urlRepository = urlRepository;
    }

    public String generateRandomCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Returns a code guaranteed (modulo an astronomically unlikely DB-level race on the
     * very last check) not to currently exist in the default-domain namespace. The final
     * authority is still the database's unique constraint at insert time — this pre-check
     * just keeps the common case from ever needing a retry-after-insert-failure round trip.
     */
    public String generateUniqueCode() {
        final int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String candidate = generateRandomCode();
            if (!urlRepository.existsByShortCodeAndDomainIsNullAndDeletedAtIsNull(candidate)) {
                return candidate;
            }
            log.warn("Short code collision on attempt {} (candidate={}) — regenerating.", attempt, candidate);
        }
        throw new IllegalStateException(
                "Failed to generate a unique short code after " + maxAttempts + " attempts. Consider increasing app.short-code.length.");
    }
}
