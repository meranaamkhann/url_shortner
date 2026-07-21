package com.urlshortener.repository;

import com.urlshortener.config.JpaAuditingConfig;
import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.enums.UrlStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-slice tests: real JPA/Hibernate query execution against an in-memory
 * H2 database (no Spring context startup overhead of a full @SpringBootTest).
 * These specifically validate the things that are easy to get subtly wrong in
 * hand-written JPQL: soft-delete filtering, the unique constraint behind alias
 * collisions, and atomic counter updates.
 *
 * @Import(JpaAuditingConfig.class) is required here: @DataJpaTest only picks up
 * @Entity classes, Spring Data repositories, and a curated set of JPA-related
 * auto-configuration — it does NOT component-scan arbitrary @Configuration
 * classes the way a full @SpringBootTest does. Without this import,
 * AuditingEntityListener has no registered AuditingHandler in this test's
 * context, so @CreatedDate/@LastModifiedDate on BaseEntity silently never fire
 * and created_at/updated_at insert as NULL, failing on the NOT NULL constraint.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UrlRepositoryTest {

    @Autowired
    private UrlRepository urlRepository;

    private Url buildUrl(String shortCode) {
        return Url.builder()
                .shortCode(shortCode)
                .longUrl("https://example.com/" + shortCode)
                .longUrlHash("hash-" + shortCode)
                .status(UrlStatus.ACTIVE)
                .build();
    }

    @Test
    void findByShortCodeAndDefaultDomain_returnsActiveUrl() {
        Url saved = urlRepository.save(buildUrl("abc1234"));

        Optional<Url> found = urlRepository.findByShortCodeAndDefaultDomain("abc1234");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByShortCodeAndDefaultDomain_excludesSoftDeletedUrls() {
        Url url = urlRepository.save(buildUrl("deleted1"));
        urlRepository.softDelete(url.getId(), Instant.now());
        urlRepository.flush();

        Optional<Url> found = urlRepository.findByShortCodeAndDefaultDomain("deleted1");

        assertThat(found).isEmpty();
    }

    // NOTE: the "duplicate short code violates the unique constraint" case is deliberately
    // NOT tested here. The real constraint (uq_urls_domain_shortcode in V1__init_schema.sql)
    // is a Postgres partial/expression index using COALESCE(domain_id::text, 'default') —
    // this @DataJpaTest slice creates its schema from Hibernate's DDL auto-generation based
    // on JPA annotations, not from the Flyway migration, so that constraint simply doesn't
    // exist in this test's database and never can. See
    // UrlRepositoryConstraintIT#duplicateShortCodeInDefaultDomain_violatesUniqueConstraint
    // for the real version of this test, which runs against actual Postgres with Flyway applied.

    @Test
    void incrementClickCount_isAtomicAndCumulative() {
        Url url = urlRepository.save(buildUrl("counter1"));
        assertThat(url.getClickCount()).isZero();

        urlRepository.incrementClickCount(url.getId());
        urlRepository.incrementClickCount(url.getId());
        urlRepository.incrementClickCount(url.getId());
        urlRepository.flush();

        Url reloaded = urlRepository.findById(url.getId()).orElseThrow();
        assertThat(reloaded.getClickCount()).isEqualTo(3);
    }

    @Test
    void softDelete_setsDeletedAtAndStatus() {
        Url url = urlRepository.save(buildUrl("softdel1"));
        Instant now = Instant.now();

        int updated = urlRepository.softDelete(url.getId(), now);
        urlRepository.flush();

        assertThat(updated).isEqualTo(1);
        Url reloaded = urlRepository.findById(url.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(UrlStatus.DELETED);
    }

    @Test
    void findExpiredButStillActive_onlyReturnsUrlsPastExpiryAndStillActive() {
        Url expired = buildUrl("expiredA");
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        urlRepository.save(expired);

        Url notYetExpired = buildUrl("futureA");
        notYetExpired.setExpiresAt(Instant.now().plusSeconds(3600));
        urlRepository.save(notYetExpired);

        Url noExpiry = buildUrl("noexpiryA");
        urlRepository.save(noExpiry);

        List<Url> results = urlRepository.findExpiredButStillActive(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(results).extracting(Url::getShortCode).containsExactly("expiredA");
    }

    @Test
    void existsByShortCodeAndDomainIsNullAndDeletedAtIsNull_reflectsSoftDeleteState() {
        Url url = urlRepository.save(buildUrl("existsCheck"));
        assertThat(urlRepository.existsByShortCodeAndDomainIsNullAndDeletedAtIsNull("existsCheck")).isTrue();

        urlRepository.softDelete(url.getId(), Instant.now());
        urlRepository.flush();

        // Once soft-deleted, the code should be considered "available" again for reuse.
        assertThat(urlRepository.existsByShortCodeAndDomainIsNullAndDeletedAtIsNull("existsCheck")).isFalse();
    }
}
