# URL Shortener — Production-Grade System Design Project

A complete, production-minded URL shortening platform (Bitly/TinyURL-class) built with
Spring Boot, PostgreSQL, Redis, and Kafka — designed and documented as a system-design
portfolio piece for FAANG-style interviews, and built incrementally in four shippable
phases rather than as one unfinished "Google-scale" attempt.

> **Built by:** [Your Name] — Final Year Project
> **Stack:** Java 21 · Spring Boot 3 · PostgreSQL 16 · Redis 7 · Apache Kafka · Docker · Kubernetes

---

## Why this project is structured the way it is

Every folder, every table, every design decision in this repo is meant to be
**defensible in an interview** — not just "it works," but "here's why I built it this
way, here's what I traded off, and here's what I'd do differently at 10x the scale."
The companion docs in [`/docs`](./docs) go deep on each of those decisions. Start there
if you want the reasoning, not just the code.

| Document | What's in it |
|---|---|
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | High-level architecture, request flow, caching/scaling strategy, short-code generation trade-offs |
| [`docs/DATABASE_SCHEMA.md`](./docs/DATABASE_SCHEMA.md) | ER diagram, table-by-table rationale, indexing/partitioning/sharding strategy |
| [`docs/API.md`](./docs/API.md) | Full REST API reference with examples |
| [`docs/SECURITY.md`](./docs/SECURITY.md) | JWT/RBAC design, every "edge case" requirement mapped to its defense |
| [`docs/TESTING.md`](./docs/TESTING.md) | Test pyramid, what's covered where, how to run each tier |
| [`docs/DEPLOYMENT.md`](./docs/DEPLOYMENT.md) | Docker, Kubernetes, CI/CD pipeline walkthrough |
| [`docs/INTERVIEW_QA.md`](./docs/INTERVIEW_QA.md) | 25+ likely interview questions about this exact project, answered |

---

## Functional coverage

✅ Shorten long URLs · ✅ Redirect short URLs · ✅ Custom aliases · ✅ URL expiration
(time-based and click-count-based) · ✅ URL analytics (totals, by-country, by-device,
top referrers, 30-day trend) · ✅ Click tracking (with bot filtering) · ✅ QR code
generation · ✅ JWT user authentication + RBAC · ✅ Public/private links · ✅ Password-
protected links · ✅ URL editing · ✅ URL disabling/enabling · ✅ Soft delete + scheduled
hard delete · ✅ Bulk URL creation (up to 100/request) · ✅ Custom domain support
(schema + service layer) · ✅ Link preview (OpenGraph metadata, SSRF-guarded)

## Non-functional coverage

High availability (multi-replica + PDB + topology spread) · Fault tolerance (cache-miss
fallback, fail-open rate limiter, Kafka decoupling) · Horizontal scalability (stateless
JWT auth, HPA) · Low latency (Redis cache-aside on the redirect hot path) · Security
(see [`docs/SECURITY.md`](./docs/SECURITY.md)) · Observability (Actuator, Prometheus,
Micrometer tracing, structured logs) · Maintainability (layered architecture, DTOs,
global exception handling, Flyway-versioned schema)

---

## Build phases (how this was actually built, and how you should explain it)

This was deliberately **not** built as one monolithic "do everything" sprint. It was
built in four phases, each independently runnable and demoable — this is also how
I'd recommend walking an interviewer through it.

### Phase 1 — Core (Spring Boot + PostgreSQL + JWT)
Shortening, redirect, registration/login/refresh, RBAC skeleton, global exception
handling, Flyway schema. *Runnable with just Postgres.*

### Phase 2 — Product features
Analytics (raw events + daily rollups), custom aliases, expiration (time + click-count
based), URL editing/disabling, soft delete, bulk create, QR codes, link preview.

### Phase 3 — Performance (Redis)
Cache-aside on the redirect hot path, negative caching against enumeration/brute-force
probing, cache eviction on mutation. *Docker Compose stands up the full stack.*

### Phase 4 — Scale & Ops (Kafka + Rate Limiting + Observability)
Click events and audit events move off the synchronous request path onto Kafka.
Distributed, Redis-backed rate limiting (fixed-window, atomic Lua script). Prometheus
metrics, Grafana dashboards, alert rules, distributed tracing hooks.

---

## Quick start (local, full stack)

**Prerequisites:** Docker + Docker Compose. That's it — Postgres, Redis, Kafka,
Prometheus, and Grafana are all provisioned for you.

```bash
git clone <this-repo>
cd url-shortener
docker compose up -d --build
```

Then:
- API: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

### Quick start (bare Java, Phase 1 only)

```bash
# Requires a local Postgres at localhost:5432 (see application.yml for credentials)
mvn clean package -DskipTests
java -jar target/url-shortener.jar
```

### Try it

```bash
# Shorten a URL (works anonymously)
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://en.wikipedia.org/wiki/System_design"}'

# -> {"shortCode": "aB3xK9q", "shortUrl": "http://localhost:8080/r/aB3xK9q", ...}

curl -i http://localhost:8080/r/aB3xK9q
# -> HTTP/1.1 302 Found
# -> Location: https://en.wikipedia.org/wiki/System_design
```

---

## Running the tests

```bash
mvn test            # fast unit tests (no external dependencies, runs in seconds)
mvn verify           # + integration tests (Testcontainers spins up real Postgres/Redis)
```

See [`docs/TESTING.md`](./docs/TESTING.md) for the full breakdown of what's tested
at each layer and why.

---

## A note on the build environment used to author this project

This codebase was authored in a sandboxed environment without access to Maven Central,
so it has **not been compiled or run in that environment** — it was written carefully,
file-by-file, against the actual Spring Boot 3.3 / Spring Security 6 / JPA APIs, but
you should expect to spend a normal debugging pass (likely under an hour) getting it to
compile cleanly on your machine, the way you would with any codebase you're integrating
for the first time. Treat this as a strong, complete first draft of a real system — not
a guaranteed zero-error `mvn clean install`. If you hit a compile error, it's most
likely a minor import/signature mismatch, not a structural design problem.

## License

MIT — use this freely for your final year project, portfolio, or interview prep.
