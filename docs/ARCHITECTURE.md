# Architecture

## 1. High-level architecture

```
                                   ┌─────────────────┐
                                   │   CDN / WAF      │   (CloudFront / Cloudflare)
                                   └────────┬─────────┘
                                            │
                                   ┌────────▼─────────┐
                                   │  Load Balancer /  │   TLS termination, L7 routing,
                                   │  Ingress (nginx)   │   coarse rate limiting
                                   └────────┬─────────┘
                                            │
                  ┌─────────────────────────┼─────────────────────────┐
                  │                         │                         │
           ┌──────▼──────┐          ┌──────▼──────┐          ┌──────▼──────┐
           │ App Pod #1   │          │ App Pod #2   │   ...    │ App Pod #N   │   Stateless Spring Boot
           │ (Spring Boot)│          │ (Spring Boot)│          │ (Spring Boot)│   instances, horizontally
           └──────┬──────┘          └──────┬──────┘          └──────┬──────┘   scaled by HPA
                  │                         │                         │
        ┌─────────┼─────────────────────────┼─────────────────────────┼─────────┐
        │         │                         │                         │         │
   ┌────▼────┐ ┌──▼───┐               ┌─────▼─────┐            ┌─────▼─────┐
   │  Redis   │ │Kafka │               │ PostgreSQL │            │ PostgreSQL │
   │ (cache + │ │(click│               │  Primary   │───────────▶│  Read      │
   │rate limit│ │+audit│               │  (writes)  │  streaming │  Replica(s)│
   │ counters)│ │events)│              └────────────┘  replication└────────────┘
   └─────────┘ └──┬───┘
                   │
            ┌──────▼──────┐
            │ Analytics    │   Consumes click-events, writes click_events rows,
            │ Consumer(s)  │   increments counters, feeds nightly rollup job
            └─────────────┘
```

Everything left of "App Pod" is infrastructure that can be swapped without touching
application code (a different LB, a different CDN). Everything right of it is what
this repo actually implements.

---

## 2. Request flow: the redirect hot path

This is the single most important flow in the system — it's hit on every click of
every short link, system-wide, and it's the one place where latency directly equals
user-perceived product quality.

```
GET /r/{shortCode}
        │
        ▼
1. RateLimitFilter checks Redis (atomic Lua INCR) — reject if over budget
        │ (pass)
        ▼
2. RedirectController -> UrlService.resolveAndTrack(shortCode)
        │
        ▼
3. CacheService.get(shortCode)  ──── HIT ────▶  skip to step 5
        │ MISS
        ▼
4. UrlRepository.findByShortCodeAndDefaultDomain(shortCode)
        │
        ├── not found ──▶ CacheService.cacheNegative(shortCode, 60s) ──▶ 404
        │
        ▼ found
   CacheService.put(shortCode, response, 1h)
        │
        ▼
5. State checks (in-memory, no extra I/O): DISABLED? DELETED? EXPIRED?
        │ all pass
        ▼
6. ClickEventProducer.publish(...) — fire-and-forget to Kafka (non-blocking)
        │
        ▼
7. HTTP 302 Found, Location: <longUrl>, Cache-Control: no-store
```

**Why each design choice:**

- **302, not 301** — browsers cache 301s permanently client-side, which would silently
  break click-tracking and make link disabling invisible to anyone who already has the
  301 cached. See `RedirectController` javadoc for the full trade-off.
- **Cache-aside, not write-through** — the cache only gets populated lazily on first
  read (cache-aside / lazy loading), not synchronously on every write. This keeps
  writes (URL creation) fast and avoids caching links that may never be clicked. The
  cost is a guaranteed cache miss on a link's very first click — an acceptable trade
  given creation and first-click are rarely the same millisecond at scale.
- **Negative caching** — a 404 on a probed/guessed short code is itself cached for 60s.
  Without this, a bot brute-forcing the keyspace (URL enumeration attack) would send
  every single guess straight to Postgres. With it, Redis absorbs the entire attack
  after the first guess of each code.
- **Kafka publish is fire-and-forget** — the redirect response does NOT wait for the
  Kafka broker ack. A click that fails to publish is a missed analytics data point,
  never a failed redirect. This is the trade-off that keeps p99 redirect latency in the
  single-digit milliseconds regardless of analytics write volume.
- **Fail-open everywhere on the hot path** — if Redis is down, `CacheService` logs and
  falls through to Postgres. If the rate limiter's Redis call fails, `RateLimiterService`
  allows the request. A cache/rate-limiter outage degrades the system to "slower, but
  still serving traffic" rather than "fully down" — see `docs/SECURITY.md` for why this
  is an intentional choice and not a gap.

---

## 3. Short code generation strategy

Implemented: **random Base62 generation + unique-constraint retry.**

```java
String code = randomBase62(7);           // 62^7 ≈ 3.5 trillion possibilities
if (exists(code)) retry();               // DB unique index is final authority
```

| Strategy | Pros | Cons | Verdict |
|---|---|---|---|
| **Auto-increment + Base62** (classic TinyURL) | Simple, zero collisions by construction | A single global counter is a write bottleneck across horizontally-scaled instances; needs a distributed ID allocator (Snowflake-style) to actually scale | Rejected for this scope — solves a problem we don't have yet, adds a moving part |
| **Pre-generated Key Generation Service (KGS)** | Zero per-request coordination; each app instance locally consumes a pre-allocated batch of keys | Needs its own service, its own storage, its own failure mode (what if it runs out mid-batch?) | **This is the real Bitly-scale answer** — noted as the natural "Phase 5" |
| **Random + retry (what's implemented)** | No extra infrastructure; collision probability is ~0.03% even at 1 billion existing codes (birthday paradox math) at 7 characters | A pathological burst of collisions *could* degrade write latency (bounded by a 5-attempt cap) | **Chosen** — right complexity for this project's scope, with a clear, documented scale-out path |

See `ShortCodeGeneratorService` javadoc for the full math and reasoning.

---

## 4. Caching strategy (Phase 3)

| Cache key | What's cached | TTL | Invalidation |
|---|---|---|---|
| `url:{shortCode}` | Serialized `UrlResponse` | 1h | Explicit evict on update/disable/delete; TTL bounds staleness from any missed eviction |
| `url:{shortCode}` (negative) | Sentinel "not found" marker | 60s | Self-expiring; absorbs enumeration/brute-force probing |
| `analyticsSummary:{urlId}` | Aggregated dashboard data | 5min | TTL-only; dashboard staleness of a few minutes is acceptable |
| `linkPreview:{url}` | OpenGraph metadata | 24h | TTL-only; destination page metadata rarely changes intraday |
| `ratelimit:*` | Fixed-window request counters | window length (60s/3600s) | Self-expiring via Redis `EXPIRE` |

**Cache-aside**, not write-through, for the `urls` cache: see section 2 above.

**Why Redis and not an in-process cache (Caffeine/Guava)?** An in-process cache is
invisible to every other instance — instance A disabling a link wouldn't evict it from
instance B's local cache, leaving B serving a disabled link until its TTL expires. Redis
gives every instance a consistent view, which is required the moment you have more than
one app replica (i.e., always, in a real deployment).

---

## 5. Load balancing & scaling strategy

- **Stateless application tier**: every request carries its own auth (JWT) — no sticky
  sessions, so any LB algorithm (round-robin, least-connections) works, and any
  instance can serve any request. This is *why* horizontal scaling is simple here.
- **HorizontalPodAutoscaler**: scales 3→20 replicas on CPU (65%) and memory (75%)
  utilization, with fast scale-up (absorb a viral link's traffic spike quickly) and
  slow scale-down (avoid flapping). See `k8s/05-hpa.yaml`.
- **Database read replicas**: analytics queries (dashboard summaries, click event
  history) are the natural candidate to route to a read replica, since they're
  read-heavy and can tolerate replication lag of a few seconds — unlike the redirect
  path's `findByShortCodeAndDefaultDomain`, which should stay on the primary (or a
  *synchronously* replicated replica) to avoid a "create a link, immediately share it,
  first click 404s" race against replication lag. This project's repository layer is
  structured so that routing reads to a replica is a datasource-routing change, not a
  rewrite (see `docs/DATABASE_SCHEMA.md` §Read Replicas).
- **Kafka consumer scaling**: the click-events consumer group scales independently of
  the API tier — partition count (6) is the ceiling on consumer parallelism, chosen to
  comfortably exceed the API tier's max replica count. This is *not* required to equal
  the app tier's replica count, since click processing is a background concern that
  can lag under burst load without affecting user-facing latency (it just means
  analytics show up a bit later).

---

## 6. Fault tolerance summary

| Failure | System behavior |
|---|---|
| Redis down | Cache reads/writes fail silently (logged); every request falls through to Postgres. Slower, not broken. Rate limiter fails open. |
| Postgres primary down | Reads can fail over to a replica (read-only mode) if configured; writes fail with a 5xx until failover completes. This is the system's actual single point of failure — see `docs/INTERVIEW_QA.md` for the mitigation discussion (multi-AZ RDS/Cloud SQL with automatic failover). |
| Kafka down | `ClickEventProducer.publish()` catches the send failure and logs it; the redirect still succeeds. Analytics for that window are simply missing rather than blocking traffic. |
| One app pod crashes | Kubernetes liveness probe restarts it; readiness probe means the LB stopped routing to it the moment it became unhealthy, before the crash. |
| A whole AZ goes down | `topologySpreadConstraints` ensures pods aren't all in one AZ; remaining AZs' pods continue serving. |

---

## 7. What would change at true Bitly/Google scale

This project's scope is explicitly "a portfolio-strength, defensible, *finishable*
system" — not a literal clone of Bitly's actual production infrastructure. At 10-100x
this scale, the next changes (in priority order) would be:

1. **Key Generation Service** replacing random+retry (see §3).
2. **Database sharding** by short-code hash once a single Postgres primary can't absorb
   write throughput — see `docs/DATABASE_SCHEMA.md` §Sharding.
3. **Multi-region active-active** with region-local Postgres + Redis and a global
   short-code namespace partitioned by region prefix, to keep redirect latency low for
   geographically distributed users.
4. **Edge-side redirect resolution** (e.g., a Cloudflare Worker holding the hottest
   N% of short codes) to resolve the most popular links without a single round trip to
   origin at all.
