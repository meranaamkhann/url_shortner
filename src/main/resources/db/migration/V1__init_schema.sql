-- ============================================================================
-- V1__init_schema.sql
-- Core schema for the URL Shortener system.
--
-- Design notes (explained further in docs/DATABASE_SCHEMA.md):
--  * UUID primary keys for users/urls -> safe to expose, no sequential
--    enumeration, and merge-friendly across shards/regions.
--  * short_code is a separate indexed column (NOT the PK) so we can change
--    encoding strategy later without touching the PK, and so we can support
--    both random aliases and custom aliases under one uniqueness constraint.
--  * click_events is an append-only, high-volume table — designed to be
--    partitioned by created_at (see partitioning note in docs).
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- ROLES
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id          SMALLSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,      -- ROLE_USER, ROLE_ADMIN
    description VARCHAR(255)
);

-- ---------------------------------------------------------------------------
-- USERS
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email               VARCHAR(255) NOT NULL UNIQUE,
    username            VARCHAR(100) NOT NULL UNIQUE,
    password_hash       VARCHAR(255) NOT NULL,
    full_name           VARCHAR(255),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    account_locked      BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_count  INT NOT NULL DEFAULT 0,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0   -- optimistic locking
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_users_email ON users(email);

-- ---------------------------------------------------------------------------
-- REFRESH TOKENS  (rotation-enabled, revocable)
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,   -- never store raw token
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by UUID REFERENCES refresh_tokens(id),
    user_agent  VARCHAR(512),
    ip_address  VARCHAR(64)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens(expires_at);

-- ---------------------------------------------------------------------------
-- CUSTOM DOMAINS  (white-labelled short links, e.g. go.acme.com)
-- ---------------------------------------------------------------------------
CREATE TABLE custom_domains (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    domain          VARCHAR(255) NOT NULL UNIQUE,
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    verification_token VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- URLS  (the core entity)
-- ---------------------------------------------------------------------------
CREATE TABLE urls (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    short_code          VARCHAR(32)  NOT NULL,
    long_url            TEXT         NOT NULL,
    long_url_hash       VARCHAR(64)  NOT NULL,   -- sha256(long_url) for fast dedup lookups
    domain_id           UUID REFERENCES custom_domains(id),
    owner_id            UUID REFERENCES users(id) ON DELETE SET NULL,  -- nullable: anonymous links
    is_custom_alias     BOOLEAN NOT NULL DEFAULT FALSE,
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',  -- PUBLIC | PRIVATE
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DISABLED | EXPIRED | DELETED
    password_hash       VARCHAR(255),             -- optional per-link password protection
    expires_at          TIMESTAMPTZ,
    max_clicks          BIGINT,                   -- optional click-count based expiry
    click_count         BIGINT NOT NULL DEFAULT 0, -- denormalized fast counter (eventually consistent w/ click_events)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,               -- soft delete marker
    version             BIGINT NOT NULL DEFAULT 0
);

-- Uniqueness is scoped to (domain_id, short_code) so the same short code can
-- exist independently under different custom domains.
CREATE UNIQUE INDEX uq_urls_domain_shortcode ON urls (COALESCE(domain_id::text, 'default'), short_code)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_urls_owner ON urls(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_urls_status ON urls(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_urls_expires_at ON urls(expires_at) WHERE expires_at IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_urls_long_url_hash ON urls(long_url_hash) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- CLICK EVENTS  (append-only, highest write volume table in the system)
-- ---------------------------------------------------------------------------
CREATE TABLE click_events (
    id           BIGSERIAL PRIMARY KEY,
    url_id       UUID NOT NULL REFERENCES urls(id) ON DELETE CASCADE,
    clicked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_hash      VARCHAR(64),         -- SHA-256 of IP, never store raw IP (PII / GDPR)
    country_code VARCHAR(2),
    city         VARCHAR(100),
    referrer     VARCHAR(512),
    user_agent   VARCHAR(512),
    device_type  VARCHAR(20),         -- DESKTOP | MOBILE | TABLET | BOT
    browser      VARCHAR(50),
    os           VARCHAR(50),
    is_bot       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_click_events_url_id_time ON click_events(url_id, clicked_at DESC);
CREATE INDEX idx_click_events_clicked_at ON click_events(clicked_at);

-- ---------------------------------------------------------------------------
-- DAILY ANALYTICS ROLLUP  (pre-aggregated for fast dashboard reads)
-- ---------------------------------------------------------------------------
CREATE TABLE url_analytics_daily (
    id            BIGSERIAL PRIMARY KEY,
    url_id        UUID NOT NULL REFERENCES urls(id) ON DELETE CASCADE,
    day           DATE NOT NULL,
    total_clicks  BIGINT NOT NULL DEFAULT 0,
    unique_clicks BIGINT NOT NULL DEFAULT 0,
    bot_clicks    BIGINT NOT NULL DEFAULT 0,
    UNIQUE (url_id, day)
);

CREATE INDEX idx_url_analytics_daily_url ON url_analytics_daily(url_id, day DESC);

-- ---------------------------------------------------------------------------
-- AUDIT LOGS  (security & compliance trail)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    action      VARCHAR(50) NOT NULL,     -- e.g. URL_CREATED, URL_DELETED, LOGIN_FAILED
    entity_type VARCHAR(50) NOT NULL,
    entity_id   VARCHAR(64),
    ip_address  VARCHAR(64),
    metadata    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_actor ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
