# Interview Q&A — This Exact Project

These are the questions a senior engineer is likely to ask about *this specific
system*, with the kind of answer that demonstrates you actually made the decisions
rather than copied them. Each answer is short on purpose — in an interview you'd
expand verbally; use these as the spine of your answer, not a script to recite.

---

### 1. Walk me through what happens when someone clicks a short link.

Request hits the LB → `RateLimitFilter` checks a Redis-backed atomic counter →
`RedirectController` calls `UrlService.resolveAndTrack` → check Redis cache for the
short code → on hit, skip straight to state checks (disabled/expired); on miss, query
Postgres by the unique `(domain, short_code)` index, populate the cache, continue →
fire-and-forget a Kafka `ClickEventMessage` → return `302 Found`. The whole thing is
designed so the *synchronous* path never waits on anything except the cache and (on a
miss) one indexed Postgres lookup — analytics writes happen entirely off that path.

### 2. Why 302 instead of 301 for the redirect?

301 (permanent) gets cached by browsers indefinitely — once cached, the browser never
hits our server again for that link, which silently breaks click tracking and makes
disabling/expiring a link invisible to anyone with it cached. 302 costs a small amount
of redirect performance (no browser-side caching) in exchange for every click being
observable and every lifecycle change being immediately enforceable. For a product
where analytics and link control are core features, that trade is clearly correct.

### 3. How do you generate short codes, and how do you handle collisions?

Random Base62, 7 characters (62^7 ≈ 3.5 trillion keyspace), with the database's unique
index as final authority — pre-check, then a bounded retry loop (5 attempts) if the
database rejects an insert. At 1 billion existing codes, collision probability per
attempt is roughly 0.03% (birthday paradox), so the retry loop essentially never
actually loops in practice. I'd switch to a pre-generated Key Generation Service — a
separate service that hands each app instance a locally-consumable batch of
pre-validated keys — if/when collision-retry overhead ever became measurable, since
that removes per-request coordination entirely. I didn't build the KGS because it's
solving a scale problem this system doesn't have yet — premature infrastructure is
itself a cost.

### 4. Why not just use an auto-increment ID and Base62-encode it (the "classic" approach)?

Two problems: it leaks information (sequential IDs let anyone estimate your total link
volume and creation rate), and it's a write bottleneck the moment you have more than
one app instance writing concurrently — every instance needs to coordinate on "what's
the next number," which is exactly the kind of shared mutable state that horizontal
scaling is supposed to let you avoid. A KGS or random+retry both sidestep this; I chose
random+retry for this project's scope because it needs zero extra infrastructure.

### 5. How do you keep the cache consistent across multiple app instances?

I don't try to keep instances' caches consistent with each other directly — I use
Redis as the *single shared cache* all instances read/write, rather than an in-process
cache like Caffeine that each instance would hold independently. That sidesteps the
consistency problem entirely: there's one cache, not N caches to keep in sync. On any
mutation (update/disable/delete), the owning request explicitly evicts the key from
Redis; the 1-hour TTL is a safety net bounding staleness for any eviction that's ever
missed (e.g., a crash mid-request).

### 6. What happens if Redis goes down?

Every Redis call in the hot path is wrapped in a try/catch that logs and falls through
to Postgres (`CacheService`) or fails open (`RateLimiterService`). The system gets
slower — every redirect now hits the database — but it doesn't go down. I made this
choice deliberately: a cache is an optimization, and an optimization failing should
degrade performance, not availability. The one thing I'd add at real scale is a
circuit breaker (Resilience4j) so that once Redis is detected as down, we stop
*attempting* and immediately fail through, rather than paying a connection-timeout
tax on every single request during the outage.

### 7. How do you prevent two concurrent requests from double-incrementing — or losing — a click count?

`UPDATE urls SET click_count = click_count + 1 WHERE id = ?` — a single atomic SQL
statement, never "read the count in application code, add one, write it back." The
read-modify-write pattern is exactly where the race condition lives; doing the
increment entirely inside one database statement removes the window where it could
happen. Postgres guarantees that statement's atomicity regardless of how many
concurrent transactions are hitting the same row.

### 8. How do you prevent two concurrent requests from both successfully claiming the same custom alias?

Defense in depth, not a single mechanism: I pre-check `existsByShortCode...` before
attempting the insert (cheap, catches the common case), but the *actual* authority is
the database's unique partial index on `(domain, short_code) WHERE deleted_at IS NULL`.
If two requests both pass the pre-check (true race), one insert succeeds and the other
throws `DataIntegrityViolationException`, which `GlobalExceptionHandler` catches and
turns into a clean `409 Conflict` — never a 500, never silent data corruption.

### 9. Why JWT instead of server-side sessions?

Statelessness. A session needs a shared store every instance can read — either sticky
LB routing to whichever instance holds the session in memory (which breaks the moment
that instance dies) or a Redis-backed session store hit on every single request. A JWT
carries its own validity proof (the signature), so any instance can authenticate a
request with zero I/O. The cost is revocation: a JWT is valid until it expires, full
stop — you can't "delete" one server-side. I mitigate that by keeping access tokens
short-lived (15 min) and making the *refresh* token the stateful, revocable part
(hashed in Postgres, rotated on every use) — so the actual "can this person still use
my system" decision gets re-checked at most every 15 minutes, with a real revocation
mechanism behind the longer-lived credential.

### 10. What stops someone from replaying a stolen refresh token?

Rotation: every refresh token is single-use. The moment it's presented, it's marked
revoked and a new one is issued in its place. If an attacker steals a refresh token
and uses it before the legitimate user does, the *legitimate user's* next refresh
attempt fails (their token was already rotated by the attacker) — which is a visible,
loggable signal something is wrong, not a silent compromise. If the legitimate user
refreshes first, the attacker's stolen copy is now dead. Either way, a stolen token
has a blast radius of "one successful use," not "valid for 7 days."

### 11. How do you stop someone from brute-forcing short codes to discover private/unlisted links?

Two layers. First, general rate limiting on every request, including redirects — a
brute-force script making thousands of guesses per minute gets throttled regardless of
which endpoint it's hitting. Second, and more specifically: every 404 (a guessed code
that doesn't exist) gets cached in Redis for 60 seconds as a negative result. The first
guess of a given wrong code hits Postgres; every subsequent identical guess — which is
exactly what a brute-force scanner does, since it's iterating systematically — gets
absorbed entirely by Redis. This means the attack's cost to *us* doesn't scale with the
size of their guess space the way it would without negative caching.

### 12. Why is CSRF protection disabled? Isn't that a vulnerability?

CSRF is a defense against a specific threat model: a malicious page tricking a
browser into replaying a user's *cookies* against our API, because cookies are
attached automatically by the browser regardless of which site initiated the request.
This API never uses cookies for authentication — only a Bearer token in an
`Authorization` header, which a browser only sends if JavaScript on our own origin
explicitly attaches it. A malicious third-party page has no way to forge that header.
So the threat CSRF protection defends against doesn't exist in this design. I documented
this explicitly in `SecurityConfig` specifically so it reads as a reasoned decision, not
an oversight — and noted that if a future client moves to httpOnly cookies for token
storage (a legitimate XSS-hardening trade-off some teams make), CSRF protection needs
to come back at that point.

### 13. How would you scale this to handle a viral link getting a million clicks in an hour?

The redirect path is already designed for this: after the first click populates the
cache, the next 999,999 clicks for that link are served entirely from Redis with no
database read at all. The thing that *would* need attention is the write side — a
million Kafka `click-events` messages need a consumer that can keep up, which is why
click-events has 6 partitions (parallelism ceiling for the consumer group) and the
processing is fully decoupled from the redirect response. If the spike were sustained
rather than a brief burst, the HPA would scale the app tier itself based on CPU, and
I'd watch the `KafkaConsumerLag` alert to know if the analytics consumer also needs
more replicas.

### 14. What's the single point of failure in this architecture, and how would you address it?

The Postgres primary. Redis failures degrade performance; Kafka failures degrade
analytics freshness; an app pod failure is absorbed by the other replicas — but a
write to Postgres genuinely cannot succeed if the primary is down, and even reads on
the redirect hot path (on a cache miss) need it. The standard mitigation is a managed,
multi-AZ database with automatic failover (RDS Multi-AZ, Cloud SQL HA) — failover
takes on the order of 60-120 seconds, during which writes fail, but it's the
well-understood, well-tested answer rather than something I'd want to build myself for
a project at this scope.

### 15. Why pre-aggregate analytics into a daily rollup table instead of just querying click_events directly?

`click_events` is the fastest-growing, highest-cardinality table in the system — a
popular link can accumulate millions of rows. A dashboard that needs "clicks per day
for the last 30 days" would otherwise need to scan and group potentially millions of
rows on every single page load. The nightly rollup job does that aggregation once,
writes 30 small rows, and the dashboard query becomes `O(30)` instead of `O(clicks)`.
The cost is a small staleness window — today's data isn't in the rollup until tonight's
job runs — which I think is the right trade for a dashboard, where "accurate as of
~24 hours ago, instant to load" beats "perfectly real-time, slow."

### 16. How would you shard this database if a single Postgres instance couldn't keep up?

By short-code hash. Almost every query in this system is naturally scoped to a single
URL — the redirect lookup, the click-event insert, the per-URL analytics query — so
there's no cross-shard join needed for any hot-path operation, which is what makes this
schema unusually shard-friendly. The one thing that gets genuinely harder is *global*
uniqueness of custom aliases, since "is this alias taken" can no longer be answered by
one shard alone — I'd route that specific check through a small, separately-replicated
lookup table (or fold it into a Key Generation Service) that isn't itself sharded.

### 17. Why soft delete instead of just deleting the row?

Two reasons. First, product: users expect a delete to be recoverable for some window,
not instant and irreversible — that's standard UX, not just an engineering nicety.
Second, and more important: click-event history references a URL by ID, and that
history is real historical data that shouldn't vanish just because the link itself was
later deleted. A hard delete would either cascade-delete months of analytics or leave
orphaned foreign keys. Soft delete (a `deleted_at` timestamp) keeps the data intact,
with a scheduled job permanently removing rows after a 30-day recovery window so the
table doesn't grow unbounded with "deleted" rows forever.

### 18. I noticed you didn't use Hibernate's `@Where`/`@SQLRestriction` for soft delete — why?

That annotation applies globally to *every* query Hibernate generates for the entity —
including the admin and scheduled-job queries that specifically need to see
soft-deleted rows (the hard-delete sweep has to find them; an admin "restore" feature
would need to find them too). Using the global filter would have silently hidden rows
from exactly the code that needs them, which is a subtle, easy-to-miss bug. I made
every repository query explicit about `deletedAt` instead — more verbose, but every
query's behavior is visible by reading it, not by knowing about a class-level
annotation elsewhere in the codebase.

### 19. How do you test something like the redirect hot path without hitting real infrastructure on every test run?

Layered, matching the test pyramid in `docs/TESTING.md`. The actual *logic* —
duplicate detection, expiry checks, status transitions — is unit-tested with Mockito
mocking out the repository and cache, so it runs in milliseconds with zero I/O. The
*wiring* — does Spring Security actually enforce what `SecurityConfig` claims, does a
real unique constraint actually fire — is verified by a smaller number of slower
Testcontainers-backed integration tests against a real (ephemeral, Dockerized)
Postgres. I deliberately didn't containerize Kafka for those integration tests; click
publishing is fire-and-forget and mocking the producer there is a reasonable trade
against meaningfully slower, flakier CI.

### 20. What would you change about this design if you were building it again from scratch?

I'd reconsider whether `click_count` needs to live as a denormalized column on `urls`
at all, versus always deriving it from `click_events` (or the rollup table) at read
time — the denormalized counter is a minor consistency surface (it can theoretically
drift from the sum of events if a write path is ever added that updates one but not
the other) that exists purely for one fast read. I kept it because "show me click count"
is the single most common read in the product and a derived aggregate query for *that*
would be wasteful, but it's the one piece of denormalization in this schema I'd want
to defend hardest if pressed, and the one I'd watch most closely for drift in
production via a periodic reconciliation job.

---

## Quick-fire definitions (in case asked cold)

- **Cache-aside**: app code checks cache, falls back to DB on miss, populates cache —
  as opposed to write-through (write to cache and DB together) or write-behind
  (write to cache, asynchronously flush to DB later).
- **Idempotent producer** (Kafka): broker-side dedup of retried sends, so a producer
  retry after a transient network blip doesn't double-publish a message.
- **Optimistic locking**: a `version` column checked-and-incremented on update; a
  concurrent conflicting write fails loudly (`OptimisticLockException`) rather than
  silently overwriting, without needing to hold a row lock for the duration of a
  user's "think time."
- **Fixed-window vs. sliding-window vs. token-bucket rate limiting**: fixed-window
  (what's implemented) is O(1) memory/CPU but allows up to 2x burst at a window
  boundary; token bucket smooths that at the cost of slightly more state per key.
  Documented as the natural next iteration in `RateLimiterService`'s javadoc.
