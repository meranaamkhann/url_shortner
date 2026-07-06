# Database Design

Full DDL: [`src/main/resources/db/migration/V1__init_schema.sql`](../src/main/resources/db/migration/V1__init_schema.sql)

## Entity-relationship overview

```
 users ──────────┬──────────< user_roles >──────────┬────── roles
   │              (M:N)                              │
   │ 1:N                                              │
   ▼
 urls ───────< (owner_id)
   │  N:1 (domain_id) ──────────────────────────────────────► custom_domains
   │
   │ 1:N
   ▼
 click_events                url_analytics_daily (1 row per url per day, rollup)
                                       ▲
                                       │ aggregated nightly from click_events

 refresh_tokens ──N:1──► users
 audit_logs ──N:1──► users (actor_id, nullable for anonymous/system actions)
```

## Table-by-table rationale

### `users`
Standard credentials table. `password_hash` only — never plaintext, never reversible
encryption (see `docs/SECURITY.md`). `failed_login_count` + `account_locked` implement
brute-force lockout in-table rather than via a separate service, since it's small,
relational data naturally scoped to a user row. `version` (optimistic locking) protects
against a lost-update race if, e.g., a login failure-counter increment and a profile
edit land concurrently.

### `roles` / `user_roles`
Classic RBAC join table. Kept as real rows (not an enum column on `users`) specifically
so multiple roles per user and future role additions don't require a schema migration —
only a new `INSERT` into `roles`.

### `refresh_tokens`
**Only the SHA-256 hash of the token is stored** (`token_hash`), never the raw value —
if this table leaks, the tokens in it are not directly replayable. `replaced_by` forms
a linked chain through token rotation events, giving forensic traceability ("this token
was used, which issued that one, which was later flagged as reused — here's the exact
chain"). Indexed on `user_id` (for "log out everywhere") and `expires_at` (for the
cleanup job).

### `urls` — the core table
- **UUID primary key**, not auto-increment: doesn't leak creation order/volume to
  anyone who can see an ID (an auto-increment ID on a public resource is itself a minor
  information leak — "this competitor created 50,000 links last month").
- **`short_code` is a separate column from the PK**, with its own unique index. This
  decouples "how we identify a row internally" from "the encoding scheme exposed to
  users" — if the short-code generation strategy ever changes (see
  `docs/ARCHITECTURE.md` §3), no foreign keys need touching.
- **Composite uniqueness**: `UNIQUE (COALESCE(domain_id, 'default'), short_code) WHERE
  deleted_at IS NULL`. Two things bundled here:
  - The `COALESCE` trick lets the *same* short code exist independently under the
    default domain and under each custom domain (`acme.com/abc` and `short.ly/abc` are
    different links).
  - The `WHERE deleted_at IS NULL` partial index means a soft-deleted link's code
    becomes immediately reusable — without it, deleting a link would permanently burn
    its short code, which is wasteful and surprising product behavior.
- **`long_url_hash`** (SHA-256 of `long_url`): TEXT columns are expensive to index
  directly; hashing to a fixed 64-char column gives an O(1) indexed duplicate-URL
  lookup (`findFirstByLongUrlHashAndOwnerId...`) without ever indexing the full URL body.
- **Soft delete via `deleted_at`**, not a hard `DELETE`: preserves click-history
  integrity (a deleted link's analytics shouldn't vanish — they're still real historical
  data) and gives a 30-day recovery window before the scheduled hard-delete sweep
  (`UrlService#hardDeleteExpiredSoftDeletes`) actually removes the row.
  **Deliberately NOT using Hibernate's `@SQLRestriction`/`@Where`** entity-level filter
  for this — that annotation applies globally to *every* query Hibernate generates,
  which would silently hide soft-deleted rows from the admin/restore/hard-delete-sweep
  queries that specifically need to see them. Every repository query is explicit about
  `deletedAt` instead — see `Url.java`'s class javadoc for the full reasoning.
- **`click_count`** is a denormalized counter, updated via a single atomic
  `UPDATE urls SET click_count = click_count + 1` (see `UrlRepository.incrementClickCount`).
  This avoids the classic read-modify-write race condition under concurrent redirects
  (two simultaneous clicks both reading count=N, both writing N+1, losing one click) —
  the increment happens entirely inside one database statement, which Postgres executes
  atomically regardless of concurrency.

### `click_events` — the highest write-volume table in the system
Append-only, never updated, rarely deleted (only via Kafka-topic-style retention, not
modeled here). One row per click, including bot clicks (kept for transparency/anti-fraud
analysis, excluded from the user-facing `click_count`). `ip_hash`, not raw IP — see
`docs/SECURITY.md` for the privacy rationale. Indexed on `(url_id, clicked_at DESC)` for
the paginated "recent clicks" view, and on `clicked_at` alone for the nightly rollup job's
date-range scans.

### `url_analytics_daily`
Pre-aggregated rollup, one row per `(url_id, day)`. Exists purely for read performance:
a dashboard querying "clicks per day for the last 30 days" against millions of raw
`click_events` rows would be both slow and wasteful to run on every page load. The
nightly job (`AnalyticsService#rollupYesterday`) trades a small staleness window
(today's partial data isn't in the rollup yet — the raw-event path covers "today" if
needed) for O(30) row reads instead of O(clicks) row scans.

### `audit_logs`
Security/compliance trail. `metadata` is `JSONB` specifically because audit events have
genuinely heterogeneous shapes (a login failure's metadata looks nothing like a bulk
delete's) — modeling this as rigid columns would mean either a very wide sparse table
or a migration every time a new audit action needs a new field. JSONB keeps the schema
stable while remaining queryable (`metadata @> '{"reason": "ACCOUNT_LOCKED"}'`).

### `custom_domains`
Supports white-labelled short links (`go.acme.com/xyz`). `verification_token` exists
because allowing a user to claim *any* domain without proving DNS control would let
User A serve phishing pages from a domain User B actually owns, if B ever pointed DNS
at our infrastructure without claiming it first — domain verification (a DNS TXT
record challenge, not implemented in full here but the schema supports it) is a
necessary anti-takeover control for any multi-tenant custom-domain feature.

---

## Indexing strategy

| Index | Purpose |
|---|---|
| `uq_urls_domain_shortcode` (partial, unique) | The redirect hot path's primary lookup — must be sub-millisecond at any URL-table size |
| `idx_urls_owner` (partial: `deleted_at IS NULL`) | "List my URLs" pagination |
| `idx_urls_expires_at` (partial: non-null expiry, not deleted) | Scheduled expiry sweep — without this, that job would table-scan the entire `urls` table every 5 minutes |
| `idx_urls_long_url_hash` | Duplicate-URL detection on creation |
| `idx_click_events_url_id_time` | Paginated click history per URL, newest first |
| `idx_click_events_clicked_at` | Nightly rollup job's date-range scan |
| `idx_refresh_tokens_expiry` | Refresh-token cleanup job |

**Partial indexes** (`WHERE deleted_at IS NULL`, `WHERE expires_at IS NOT NULL`) are used
deliberately over full-table indexes wherever a query only ever cares about a subset of
rows — this keeps the index smaller, faster to scan, and cheaper to maintain on every
write, at zero functional cost since the excluded rows are never the ones these queries
need.

---

## Partitioning strategy (for `click_events` at scale)

Not implemented in `V1` (premature for a project this size), but designed for: Postgres
**declarative range partitioning by `clicked_at`, monthly**.

```sql
CREATE TABLE click_events (...) PARTITION BY RANGE (clicked_at);
CREATE TABLE click_events_2026_06 PARTITION OF click_events
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
```

Why: `click_events` is the fastest-growing table in the system by orders of magnitude.
Monthly partitions mean (a) the nightly rollup job only ever scans one or two recent
partitions instead of the whole table, (b) old partitions can be detached and archived
to cold storage (S3 + Parquet) instead of bloating the primary database indefinitely,
and (c) `VACUUM`/index maintenance operates per-partition, which is dramatically
cheaper than vacuuming one ever-growing table.

---

## Sharding strategy (beyond a single Postgres instance)

Not implemented here — this project's scope assumes a single (replicated) Postgres
instance is sufficient, which is true for a very large amount of real-world write
volume. The documented path beyond that ceiling:

**Shard key: `short_code` (or its hash).** Every hot-path query (`urls` lookups,
`click_events` inserts) is naturally scoped to a single `url_id`/`short_code`, which
makes this an unusually shard-friendly schema — there's no cross-shard join required
for the redirect hot path or for per-URL analytics. The one place sharding gets
genuinely harder is **global uniqueness of custom aliases** (alias collision checking
must be cluster-wide, not per-shard) — solved by routing the "is this alias taken?"
check through a small, separately-replicated lookup table (or a KGS, see
`docs/ARCHITECTURE.md` §3) that isn't itself sharded.

---

## Read replicas

Postgres streaming replication, async by default. Routing:
- **Route to replica**: analytics dashboard queries, click-event history, admin user
  listing — all read-heavy, all tolerant of a few seconds of replication lag.
- **Route to primary**: the redirect hot path's `findByShortCodeAndDefaultDomain`, and
  anything immediately following a write in the same user flow (read-your-own-writes:
  a user creating a link and immediately viewing it shouldn't see stale/missing data).

Implementation hook: Spring's `@Transactional(readOnly = true)` annotations already
mark every read-only query in this codebase (see `AnalyticsService`, `UrlService`
getters) — wiring a `AbstractRoutingDataSource` that inspects
`TransactionSynchronizationManager.isCurrentTransactionReadOnly()` and routes
accordingly is a configuration-only change, not a service-layer rewrite.
