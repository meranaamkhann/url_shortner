# Deployment

## Docker

### `Dockerfile` — multi-stage build
**Stage 1** (`maven:3.9.9-eclipse-temurin-21`): compiles and packages the JAR.
**Stage 2** (`eclipse-temurin:21-jre-jammy`): runtime-only — no Maven, no source code,
no build cache. This matters for two reasons: image size (the final image only carries
a JRE + one JAR, not an entire build toolchain) and attack surface (fewer binaries
present in the runtime container).

Runs as a **non-root user** (`appuser`) — if the JVM process is ever compromised via a
dependency vulnerability, this limits what the attacker's process can do inside the
container. Sets container-aware JVM flags (`-XX:MaxRAMPercentage=75.0`) so the JVM
respects the **container's** cgroup memory limit rather than defaulting to a fraction
of the host's total memory — a classic "works on my laptop, OOMKilled in production"
bug class avoided.

### `docker-compose.yml` — full local stack
One command (`docker compose up -d --build`) brings up the app plus every piece of
infrastructure it depends on: Postgres, Redis, Kafka+Zookeeper, Prometheus, and
Grafana. This is what makes the project fully demoable on a laptop with nothing
installed except Docker.

---

## Kubernetes (`/k8s`)

| Manifest | Purpose |
|---|---|
| `00-namespace.yaml` | Isolates this app's resources |
| `01-configmap.yaml` | Non-secret config (profile, base URL, pool sizes) |
| `02-secret-template.yaml` | **Template only** — real secrets come from a secret manager / CI, never committed |
| `03-deployment.yaml` | The workload — 3→20 replicas, rolling updates, health probes, resource limits |
| `04-service.yaml` | Stable internal ClusterIP |
| `05-hpa.yaml` | CPU/memory-based autoscaling |
| `06-ingress.yaml` | TLS termination, host-based routing, ingress-level rate limiting |
| `07-pdb.yaml` | PodDisruptionBudget — survives node drains/cluster upgrades |

### Key deployment design decisions

- **`maxUnavailable: 0`** on the rolling update strategy: zero-downtime deploys. A new
  pod must pass readiness before an old one is terminated.
- **Three separate probes** (liveness/readiness/startup), each with a distinct job:
  - *Liveness* — "is the JVM alive?" Deliberately lightweight (no DB check) so a
    transient downstream outage doesn't trigger a pointless restart loop on an
    otherwise-healthy pod.
  - *Readiness* — "is this pod ready for traffic?" Checks DB/Redis connectivity via
    Spring Boot's readiness health group. A failing pod is pulled from the load
    balancer's rotation *without* being restarted — the right response to "the
    database is temporarily unreachable" is "stop sending this pod traffic," not
    "kill and restart a perfectly healthy JVM."
  - *Startup* — gives a slow cold start (Flyway migrations running, JIT warmup) up to
    2 minutes before liveness probing even begins.
- **`topologySpreadConstraints`** across `topology.kubernetes.io/zone`: protects
  against an entire AZ failure taking out every replica simultaneously.
- **`PodDisruptionBudget` (`minAvailable: 2`)**: without this, a cluster-autoscaler
  node drain during a routine upgrade could legally evict every replica at once if
  they happen to land on nodes being drained back-to-back.
- **Graceful shutdown**: `server.shutdown=graceful` (Spring Boot) + a `preStop` hook
  that sleeps 5s before SIGTERM — gives in-flight requests time to complete and gives
  the load balancer time to notice the pod is terminating and stop routing to it,
  before the process actually exits.

---

## CI/CD (`.github/workflows`)

### `ci.yml` — runs on every push/PR
1. **Build & test**: compile → unit tests → Testcontainers integration tests → JaCoCo
   coverage → package. Runs against real Postgres/Redis service containers.
2. **Static analysis**: OWASP Dependency-Check (fails the build on CVSS ≥ 8 known
   vulnerabilities in dependencies), SpotBugs.
3. **Docker build validation + Trivy scan**: confirms the image builds cleanly and has
   no critical/high CVEs in its OS packages or dependencies — *before* anything is
   ever pushed to a registry.

### `cd.yml` — runs on merge to `main`
1. **Build & push** the image to GHCR, tagged with the immutable commit SHA (never
   deploys a mutable `:latest` tag to an environment that matters).
2. **Deploy to staging**, then run a smoke test (`/actuator/health` returns `UP`).
3. **Deploy to production** — gated behind a GitHub Environment with required
   reviewers (a manual approval gate, configured in repo settings, not in this YAML).
4. **Automatic rollback on failure**: if the production smoke test fails,
   `kubectl rollout undo` immediately reverts to the previous working revision.

This is the standard "build once, promote the same artifact through environments"
pattern — staging and production deploy the *exact same image digest*, eliminating
"it worked in staging but the prod build was different" as a failure class.

---

## Observability

- **Health**: `/actuator/health` (liveness/readiness groups configured explicitly —
  see `application.yml`), `/actuator/info`.
- **Metrics**: `/actuator/prometheus` (Micrometer + `micrometer-registry-prometheus`),
  scraped by Prometheus every 15s (`monitoring/prometheus.yml`).
- **Alerting**: `monitoring/alert-rules.yml` — p99 redirect latency, 5xx error rate,
  cache hit rate, HikariCP pool exhaustion, Kafka consumer lag, instance-down.
- **Dashboards**: Grafana auto-provisioned with the Prometheus datasource
  (`monitoring/grafana-datasources.yml`); build panels against the alert metrics above
  for a first dashboard.
- **Distributed tracing**: Micrometer Tracing + Brave + Zipkin reporter wired in
  (`pom.xml`), `traceId`/`spanId` included in every log line (`logback-spring.xml`
  pattern) and in every `ApiError` response — so a user-reported error can be
  correlated directly to server-side logs and a trace, without grepping timestamps.
- **Logging**: structured console pattern includes trace/span IDs by default; the
  `logback-spring.xml` appender is isolated specifically so swapping in a JSON encoder
  for shipping to Loki/ELK in production is a one-line change, not a rewrite.

## Production deployment workflow (end-to-end)

```
Developer pushes to a feature branch
        │
        ▼
ci.yml runs: build, test, static analysis, Docker/Trivy scan
        │ (PR approved & merged to main)
        ▼
cd.yml runs: build immutable image -> push to GHCR
        │
        ▼
Deploy to staging -> automated smoke test
        │ (passes)
        ▼
Manual approval gate (GitHub Environment "production" reviewers)
        │ (approved)
        ▼
kubectl set image (same SHA-tagged digest used in staging)
        │
        ▼
Rolling update, maxUnavailable: 0, readiness-gated
        │
        ▼
Production smoke test
        │
   ┌────┴────┐
 pass      fail
   │          │
 done   kubectl rollout undo (automatic)
```
