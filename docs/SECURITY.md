# Security

This document maps every security requirement and edge case from the project brief to
exactly where it's implemented, so it's easy to verify (and to discuss in an interview)
that nothing was hand-waved.

## Authentication: JWT + refresh token rotation

- **Access tokens**: short-lived (15 min), stateless, HMAC-SHA256 signed (`JwtTokenProvider`).
  Stateless means any of N horizontally-scaled instances can validate a request with
  zero shared state / zero Redis or DB lookup on the hot path.
- **Refresh tokens**: longer-lived (7 days), but **stateful** — only their SHA-256 hash
  is persisted (`refresh_tokens.token_hash`), and every use **rotates** them (the
  presented token is revoked, a new one issued; `RefreshTokenService#rotate`). This
  means a stolen refresh token can be replayed at most once before the legitimate
  user's next refresh invalidates it — and if both the attacker and the legitimate
  user try to use the same stolen token, whichever goes second gets a hard
  `401 INVALID_TOKEN`, which is itself a strong, loggable signal of token theft.
- **Why JWT over server-side sessions**: sessions need a shared store (sticky LB
  routing or a Redis session store on every request); JWTs push that cost to issuance
  time only. The trade-off — a revoked/role-changed user's *access* token stays valid
  until it naturally expires (max 15 min) — is accepted and documented, not hidden.

## RBAC

Two roles (`ROLE_USER`, `ROLE_ADMIN`), enforced at two layers for defense-in-depth:
1. **Coarse**: `SecurityConfig`'s `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`.
2. **Fine**: `@PreAuthorize` on `AdminController`, plus explicit ownership checks in
   `UrlService#findOwnedUrl` (an authenticated non-owner gets `403 FORBIDDEN_ACTION`,
   not a silent bypass) — this is what stops User A from disabling User B's link just
   by guessing/enumerating a UUID.

## Rate limiting (Phase 4)

`RateLimiterService` + `RateLimitFilter` — Redis-backed, atomic fixed-window counters
via a single Lua `EVAL` (INCR + conditional EXPIRE), keyed by user ID when
authenticated, by IP when anonymous. Three tiers: general per-minute, a stricter
per-hour budget specifically on URL creation (the most abuse-attractive, most
expensive operation), all configurable via `application.yml`.

**Fails open**: if Redis itself is unreachable, requests are allowed through rather
than blocked (`RateLimiterService.isAllowed` catches and logs, returns `true`). A rate
limiter outage degrading "no abuse protection for a few minutes" is a better failure
mode than it degrading "the entire API returns 429 to everyone."

## CSRF / XSS / SQL injection / secure headers

- **CSRF disabled, deliberately**: CSRF protects *cookie*-based session auth from
  being riden by a malicious page. This system uses Bearer tokens in an
  `Authorization` header, which a browser never auto-attaches the way it does
  cookies — so the CSRF threat model doesn't apply. Documented explicitly in
  `SecurityConfig` so this isn't mistaken for an oversight; if a future client
  switches to httpOnly cookies for token storage, CSRF protection must be reinstated
  at that point.
- **XSS**: no server-rendered HTML templates exist in this API-only backend (a
  classic reflected/stored XSS vector), so the main residual surface is JSON
  responses echoing user input — Jackson escapes by default, and `Content-Security-Policy:
  default-src 'self'` is set as defense-in-depth regardless (`SecurityConfig.headers`).
- **SQL injection**: 100% JPA/Hibernate parameterized queries (`@Query` with named
  parameters), zero string-concatenated SQL anywhere in the codebase.
- **Secure headers**: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Strict-Transport-Security` (1 year, includeSubDomains), `Referrer-Policy`,
  `Permissions-Policy`, and CSP — all set in `SecurityConfig.filterChain`.

## Input validation & URL sanitization

`UrlValidator` is the single, well-tested security boundary every long URL passes
through before persistence (see its class javadoc). It rejects:
- Malformed URLs / URLs over 2048 chars
- Disallowed schemes (`javascript:`, `data:`, `vbscript:`, `file:` — XSS/RCE vectors)
- A small local suspicious-pattern blocklist (documented as a stand-in for a real
  threat-intel feed like Google Safe Browsing in production)
- **Self-referential links** (shortening a link back to this service) — prevents
  redirect loops
- **Chained shorteners** (bit.ly, tinyurl.com, etc.) — prevents using this service to
  obscure a destination behind several hops, a common phishing obfuscation technique
- IDN/punycode-normalizes hosts to catch homograph spoofing (e.g. Cyrillic `а` vs
  Latin `a` in a spoofed domain)

`AliasValidator` separately validates custom aliases: format (`3-32` chars,
`[a-zA-Z0-9_-]`) and a reserved-word blocklist (`admin`, `api`, `login`, etc.) so a
custom alias can never shadow a real API route.

## SSRF protection

The **only** place this server makes an outbound request to a user-supplied URL is
link preview fetching (`LinkPreviewService`). `UrlValidator#isSafeForServerSideFetch`
resolves the host and rejects loopback / link-local / site-local / multicast
addresses — without this, a malicious `longUrl` like `http://169.254.169.254/` could
be used to probe cloud instance metadata endpoints *through* our server. Additionally
bounded by a 4s timeout and a 2MB response cap to prevent slow-loris-style thread
exhaustion or memory-exhaustion DoS via the preview fetch.

## Audit logging

`AuditLogService` (synchronous-ish, `@Async`) + Kafka `audit-events` topic (Phase 4,
for downstream SIEM/fraud-detection fan-out) — every security-relevant action
(registration, login success/failure, token refresh, URL lifecycle changes) is
recorded with actor, IP, and structured metadata. Audit writes are best-effort by
design (never roll back the business transaction that triggered them).

---

## Edge cases — mapped to implementation

| Edge case | Where it's handled |
|---|---|
| **Duplicate URLs** | `UrlService#create` — same owner + same `longUrl` (compared via `long_url_hash`) returns the existing link instead of creating a duplicate |
| **Race conditions** (concurrent click increments) | Atomic `UPDATE urls SET click_count = click_count + 1` (`UrlRepository.incrementClickCount`) — never read-modify-write in application code |
| **Concurrent requests** (two requests creating the same alias simultaneously) | Pre-check + DB unique constraint as final authority; `GlobalExceptionHandler#handleDataIntegrity` catches the loser of the race and returns a clean `409`, not a 500 |
| **Alias collisions** | `DuplicateAliasException` → `409 ALIAS_ALREADY_EXISTS`; soft-deleted aliases become reusable (partial unique index) |
| **Database failures** | Global exception handler never leaks a stack trace; connection pool (HikariCP) bounds and times out cleanly rather than hanging |
| **Cache failures** | `CacheService` catches every Redis exception and falls through to Postgres — see `docs/ARCHITECTURE.md` §2 |
| **Expired links** | Checked at redirect time (`Url#isExpired` — time- and click-count-based) *and* swept by a scheduled job (`UrlService#markExpiredUrls`) so the status is eventually consistent even for links nobody clicks |
| **Deleted links** | Soft delete (`deleted_at`) excluded from every active-state query explicitly; redirect returns `404`, not a 500 or a leak of "this existed once" |
| **Invalid URLs** | `UrlValidator` — see above |
| **Malicious URLs** | `UrlValidator` — see above |
| **Redirect loops** | Self-referential and chained-shortener rejection in `UrlValidator` |
| **Distributed deployment issues** | Stateless JWT auth (no sticky sessions needed), Redis as the single source of truth for cache/rate-limit state across instances |
| **Rate limiting abuse** | `RateLimiterService` — see above |
| **DDoS attempts** | Layered: ingress-level `limit-rps` annotation (`k8s/06-ingress.yaml`) as a coarse first line, then the application-level Redis rate limiter as a finer-grained second line |
| **URL enumeration attacks** | Negative caching (`CacheService.cacheNegative`) — see `docs/ARCHITECTURE.md` §2 |
| **Brute-force alias discovery** | Same negative-cache mechanism + general rate limiting on the redirect path itself |
| **Analytics spam** | Bot detection (`UserAgentParser`) excludes bot clicks from the user-facing `click_count`, while still recording them for anti-fraud visibility |
| **Bot traffic** | `UserAgentParser.parse(...).bot()` — pattern-matched against known bot/crawler/script signatures |
| **Link hijacking attempts** | Ownership checks (`UrlService#findOwnedUrl`) on every mutating endpoint — a non-owner gets `403`, never a silent no-op or a successful hijack |

---

## What's explicitly *not* hardened here (and why that's stated, not hidden)

- **GeoIP is stubbed** (`GeoLocationService`) — a real MaxMind GeoLite2 database isn't
  bundled (license + 70MB binary), but the integration point is isolated to one class.
- **The malicious-URL pattern list is a local stand-in**, not a real threat-intel feed —
  documented in `UrlValidator`'s javadoc as the production integration point (Google
  Safe Browsing API or an internal Kafka-fed blocklist).
- **Kafka consumer error handling** (poison-pill messages, dead-letter routing) is
  documented in `ClickEventConsumer`'s javadoc rather than fully implemented with a
  configured `DefaultErrorHandler` — called out explicitly so it reads as a known,
  intentional scope boundary rather than an oversight.
