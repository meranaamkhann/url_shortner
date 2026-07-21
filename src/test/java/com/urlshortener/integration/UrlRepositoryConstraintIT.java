package com.urlshortener.integration;

import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.enums.UrlStatus;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the real database constraint behind alias collisions — the partial/expression
 * unique index defined in V1__init_schema.sql:
 *
 *   UNIQUE INDEX uq_urls_domain_shortcode ON urls (COALESCE(domain_id::text, 'default'), short_code)
 *
 * This can only be tested meaningfully against real Postgres: it's a Postgres-specific
 * expression index that Hibernate's DDL auto-generation (used by the fast @DataJpaTest
 * repository-slice tests) has no way to know about or reproduce, since it comes from the
 * Flyway migration rather than any JPA annotation on the entity. AbstractIntegrationTest
 * runs with spring.flyway.enabled=true and hibernate.ddl-auto=validate against a real
 * Postgres Testcontainer, so this is the one place that constraint is actually exercised.
 *
 * In production this constraint is a safety net, not the primary defense — UrlService
 * checks alias availability before inserting (see UrlService#resolveShortCode) and this
 * index exists to correctly reject the loser of a genuine concurrent-request race (see
 * GlobalExceptionHandler#handleDataIntegrity).
 */
class UrlRepositoryConstraintIT extends AbstractIntegrationTest {

    @Autowired
    private UrlRepository urlRepository;

    @Test
    void duplicateShortCodeInDefaultDomain_violatesUniqueConstraint() {
        urlRepository.saveAndFlush(buildUrl("dupCode1"));

        assertThatThrownBy(() -> urlRepository.saveAndFlush(buildUrl("dupCode1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Url buildUrl(String shortCode) {
        return Url.builder()
                .shortCode(shortCode)
                .longUrl("https://example.com/" + shortCode)
                .longUrlHash("hash-" + shortCode)
                .status(UrlStatus.ACTIVE)
                .build();
    }
}
