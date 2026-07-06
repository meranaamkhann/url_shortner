# Testing Strategy

```
                    ▲
                   ╱ ╲          A handful — full HTTP stack, real Postgres/Redis
                  ╱ IT╲         (Testcontainers), Kafka mocked. Slow (~seconds each),
                 ╱─────╲        run via `mvn verify` / Failsafe. *IT.java suffix.
                ╱       ╲
               ╱  Slice  ╲      @DataJpaTest against H2 — real JPQL execution,
              ╱   tests   ╲     no Spring context overhead. Catches query bugs
             ╱─────────────╲    (soft-delete filtering, unique constraints).
            ╱               ╲
           ╱    Unit tests    ╲  The bulk of the suite. Pure JVM, no Spring context,
          ╱___________________╲ no I/O. Milliseconds each. Mockito for collaborators.
```

## Why this shape

The project brief asks for "unit tests, integration tests, repository tests, security
tests, load testing strategy." Rather than writing a thin, token test for each category,
the suite is weighted toward fast unit tests (cheapest to run, cheapest to maintain,
fastest feedback loop) with integration tests reserved for the things that *can only*
be verified end-to-end (does the Spring Security filter chain actually reject an
unauthenticated request? does refresh token rotation actually invalidate the old token
in a real database transaction?).

## What's covered where

### Unit tests (`mvn test`, fast, no external dependencies)

| Class under test | What's verified |
|---|---|
| `UrlValidatorTest` | Every malicious-URL/SSRF/redirect-loop edge case from `docs/SECURITY.md` |
| `AliasValidatorTest` | Format rules, reserved-word blocklist |
| `Base62EncoderTest` | Encode/decode round-trip correctness |
| `HashUtilTest` | SHA-256 determinism and format |
| `JwtTokenProviderTest` | Token round-trip, tampered-signature rejection, cross-secret rejection, malformed-token rejection |
| `UrlServiceTest` | Duplicate-URL dedup, alias-collision rejection, not-found/disabled/expired redirect semantics, click-event publish on success |
| `AuthServiceTest` | Registration validation, login success/failure, **brute-force lockout after 10 failures**, locked-account short-circuit (never compares password), no username-enumeration leak in error messages |
| `RateLimiterServiceTest` | Under/at/over-limit boundary behavior, **fail-open on Redis outage** |

### Repository tests (`@DataJpaTest`, H2, still part of `mvn test`)

`UrlRepositoryTest` — runs real JPQL against an in-memory database to catch the class
of bug that's invisible in a mocked-repository unit test: does the soft-delete filter
actually exclude deleted rows from every relevant query? Does the unique constraint
actually throw on a duplicate short code? Is the click-count increment actually
cumulative under repeated calls?

### Integration tests (`mvn verify`, Testcontainers, slower)

| Class | What's verified |
|---|---|
| `AuthFlowIT` | Full HTTP register → login → refresh → reuse-rejected lifecycle against a **real** Postgres, through the **real** Spring Security filter chain |
| `UrlShortenAndRedirectIT` | Anonymous shortening + redirect, alias collision via real HTTP `409`, malicious URL rejection, **cross-user ownership enforcement** (`403` when User B tries to disable User A's link), unauthenticated access to a protected endpoint |

These specifically exist to catch the gap unit tests with mocks structurally cannot:
"does Spring Security actually wire up the way `SecurityConfig` claims it does?"

### Security tests

Security-specific behavior is woven through the suite rather than isolated in one
file, because that's where it's most meaningfully tested — `JwtTokenProviderTest`
(token forgery/tampering), `AuthServiceTest` (brute-force/lockout/enumeration),
`UrlShortenAndRedirectIT` (cross-user authorization, unauthenticated rejection),
`UrlValidatorTest` (SSRF/injection-style URL payloads).

### Load testing strategy (documented, not automated in this repo)

Not part of the Maven build (load tests are an operational concern, run against a
staging environment, not CI) — but the documented approach:

```bash
# k6 example targeting the redirect hot path specifically, since that's the
# endpoint where latency directly equals product quality
k6 run --vus 500 --duration 60s redirect-load-test.js
```

Key things to load-test before calling this "production ready":
1. **Redirect p50/p95/p99 latency** under sustained load, with cache warm vs. cold
2. **Cache hit-rate behavior** under realistic Zipfian traffic distribution (a small
   number of links get most of the clicks — this is what makes caching effective in
   the first place)
3. **Write throughput** on `/api/v1/urls` under the rate limiter's configured ceiling
4. **Kafka consumer lag** under a simulated traffic spike, to validate the
   `KafkaConsumerLag` Prometheus alert threshold is meaningful
5. **HPA scale-up reaction time** — how long from "CPU crosses 65%" to "new pod
   passes readiness and starts serving traffic"

## Running the suite

```bash
mvn test                    # unit + repository (H2) tests only — seconds
mvn verify                  # + integration tests (Testcontainers) — needs Docker, ~1-2 min
mvn test jacoco:report      # generates target/site/jacoco/index.html coverage report
```
