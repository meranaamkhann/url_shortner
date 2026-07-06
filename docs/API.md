# API Reference

Full interactive docs (once running): `http://localhost:8080/swagger-ui.html`
OpenAPI spec: `http://localhost:8080/v3/api-docs`

All endpoints return the standard error shape on failure (see **Error format** below).
All list endpoints support `page` (0-indexed) and `size` (capped server-side, typically
at 100) query parameters and return a `PagedResponse<T>` envelope. 🔒 = requires a
valid `Authorization: Bearer <accessToken>` header.

---

## Authentication

### `POST /api/v1/auth/register`
```json
// Request
{
  "email": "alice@example.com",
  "username": "alice",
  "password": "SecurePass123",
  "fullName": "Alice Anderson"
}
```
```json
// 201 Created
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900,
  "user": { "id": "...", "username": "alice", "email": "alice@example.com", "roles": ["ROLE_USER"] }
}
```
Password policy: min 8 chars, at least one upper, one lower, one digit (enforced via
`@Pattern` on `RegisterRequest`). `409 CONFLICT` (`USER_ALREADY_EXISTS`) if the email or
username is taken.

### `POST /api/v1/auth/login`
```json
{ "usernameOrEmail": "alice", "password": "SecurePass123" }
```
`401 UNAUTHORIZED` (`INVALID_CREDENTIALS`) on bad credentials — deliberately identical
error for "wrong password" and "user doesn't exist" to avoid leaking which usernames
are registered (username enumeration). Account locks after 10 consecutive failures.

### `POST /api/v1/auth/refresh`
```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```
Returns a brand-new access+refresh token pair; the presented refresh token is
immediately revoked (rotation — see `docs/SECURITY.md`). Reusing an already-rotated
token returns `401` (`INVALID_TOKEN`).

### `POST /api/v1/auth/logout` 🔒
Revokes all of the current user's refresh tokens. `204 No Content`.

---

## URL management

### `POST /api/v1/urls`
Works **authenticated or anonymous**. If authenticated, the link is owned by the
caller; otherwise it's an anonymous public link.

```json
// Request — only longUrl is required
{
  "longUrl": "https://example.com/a/very/long/path?with=query&params=here",
  "customAlias": "my-campaign-2026",
  "expiresAt": "2026-12-31T23:59:59Z",
  "maxClicks": 1000,
  "visibility": "PUBLIC",
  "password": "optional-link-password"
}
```
```json
// 201 Created
{
  "id": "5f8d0d55-...",
  "shortCode": "my-campaign-2026",
  "shortUrl": "http://localhost:8080/r/my-campaign-2026",
  "longUrl": "https://example.com/a/very/long/path?with=query&params=here",
  "status": "ACTIVE",
  "visibility": "PUBLIC",
  "customAlias": true,
  "passwordProtected": true,
  "expiresAt": "2026-12-31T23:59:59Z",
  "maxClicks": 1000,
  "clickCount": 0,
  "createdAt": "2026-06-20T10:00:00Z",
  "updatedAt": "2026-06-20T10:00:00Z"
}
```
| Error | Cause |
|---|---|
| `400 INVALID_URL` | Malformed URL, disallowed scheme, missing host, bad alias format |
| `400 MALICIOUS_URL_REJECTED` | Suspicious pattern, self-referential link, chained shortener |
| `409 ALIAS_ALREADY_EXISTS` | Custom alias already taken |
| `429 RATE_LIMIT_EXCEEDED` | Hourly shorten-rate budget exceeded |

Calling this twice with the same `longUrl` as the same owner and no `customAlias`
returns the **existing** link rather than creating a duplicate (see `UrlService#create`).

### `POST /api/v1/urls/bulk` 🔒
```json
{ "items": [ { "longUrl": "https://a.com" }, { "longUrl": "https://b.com" } ] }
```
Max 100 items per request. Returns `201` with an array of `UrlResponse`.

### `GET /api/v1/urls` 🔒
Paginated list of the caller's own URLs. `?page=0&size=20`

### `GET /api/v1/urls/{id}` 🔒
Owner or admin only — `403 FORBIDDEN_ACTION` otherwise.

### `PATCH /api/v1/urls/{id}` 🔒
Partial update — any subset of `longUrl`, `expiresAt`, `maxClicks`, `visibility`,
`password`, `removePassword`.

### `POST /api/v1/urls/{id}/disable` / `/enable` 🔒
`204 No Content`. Disabled links return `410 GONE` (`LINK_DISABLED`) on redirect.

### `DELETE /api/v1/urls/{id}` 🔒
Soft delete (recoverable for 30 days). `204 No Content`.

### `DELETE /api/v1/urls/{id}/permanent` 🔒 *(ROLE_ADMIN)*
Immediate, irreversible hard delete.

### `GET /api/v1/urls/{id}/qrcode` 🔒
Returns `image/png` directly — embed as `<img src="...">`.

### `GET /api/v1/urls/{id}/qrcode/base64` 🔒
```json
{ "shortUrl": "http://localhost:8080/r/abc123", "base64PngImage": "iVBORw0KGgo..." }
```

---

## Redirect (the public hot path)

### `GET /r/{shortCode}`
`302 Found` with `Location` header on success.

| Status | Error code | Meaning |
|---|---|---|
| `404` | `RESOURCE_NOT_FOUND` | No such short code |
| `410` | `LINK_DISABLED` | Owner disabled the link |
| `410` | `LINK_EXPIRED` | Past `expiresAt` or `maxClicks` reached |
| `401` | `LINK_PASSWORD_REQUIRED` | Link requires a password — use the `/access` endpoint below |

### `POST /r/{shortCode}/access`
For password-protected links.
```json
{ "password": "the-link-password" }
```
`302 Found` on success, `401 INVALID_CREDENTIALS` on wrong password.

---

## Analytics

### `GET /api/v1/urls/{id}/analytics/summary` 🔒
```json
{
  "shortCode": "my-campaign-2026",
  "totalClicks": 4821,
  "uniqueClicks": 3190,
  "botClicks": 112,
  "clicksByCountry": { "US": 2200, "IN": 980, "GB": 540 },
  "clicksByDevice": { "MOBILE": 2900, "DESKTOP": 1700, "BOT": 112 },
  "topReferrers": [ { "referrer": "https://twitter.com", "count": 1500 } ],
  "last30Days": [ { "day": "2026-06-19", "totalClicks": 210, "uniqueClicks": 180 } ]
}
```

### `GET /api/v1/urls/{id}/analytics/events` 🔒
Paginated raw click-event log.

---

## Public

### `GET /api/v1/public/link-preview?url=https://example.com`
```json
{ "url": "...", "title": "...", "description": "...", "imageUrl": "...", "siteName": "..." }
```
SSRF-guarded — see `docs/SECURITY.md`.

---

## Admin (`ROLE_ADMIN` required)

### `GET /api/v1/admin/users` — paginated user list
### `GET /api/v1/admin/audit-logs` — paginated, most-recent-first audit trail

---

## Error format

Every 4xx/5xx response uses this exact shape:

```json
{
  "timestamp": "2026-06-20T10:00:00Z",
  "status": 404,
  "errorCode": "RESOURCE_NOT_FOUND",
  "message": "Short URL not found: abc123",
  "path": "/r/abc123",
  "fieldErrors": null,
  "traceId": "a1b2c3d4..."
}
```

Validation failures additionally populate `fieldErrors`:
```json
{
  "errorCode": "VALIDATION_FAILED",
  "fieldErrors": [ { "field": "email", "message": "Email must be a valid email address" } ]
}
```

`traceId` correlates the response with server-side logs/distributed traces for
debugging — see `docs/DEPLOYMENT.md` §Observability.
