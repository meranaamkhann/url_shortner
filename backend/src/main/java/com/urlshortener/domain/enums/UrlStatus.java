package com.urlshortener.domain.enums;

/**
 * Lifecycle states of a short URL.
 *
 * ACTIVE   -> redirects normally
 * DISABLED -> owner/admin manually disabled it (recoverable)
 * EXPIRED  -> past expires_at or max_clicks reached (system-set; recoverable by extending expiry)
 * DELETED  -> soft-deleted; hidden everywhere except admin hard-delete jobs
 */
public enum UrlStatus {
    ACTIVE,
    DISABLED,
    EXPIRED,
    DELETED
}
