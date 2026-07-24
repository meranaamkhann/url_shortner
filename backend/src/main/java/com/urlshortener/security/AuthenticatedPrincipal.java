package com.urlshortener.security;

import java.util.UUID;

/**
 * What we store as the Authentication#getPrincipal() for JWT-authenticated requests.
 * Deliberately lightweight (just id + username) rather than the full UserDetails/User
 * entity, since this is reconstructed from the token on every request and we don't want
 * to imply it carries a fresh, DB-consistent view of the user (e.g. updated roles take
 * effect only after the access token expires/refreshes, which is an accepted trade-off
 * of stateless JWTs and is called out in docs/ARCHITECTURE.md).
 */
public record AuthenticatedPrincipal(UUID id, String username) {
}
