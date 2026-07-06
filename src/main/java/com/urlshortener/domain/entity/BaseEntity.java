package com.urlshortener.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Common audit + optimistic-locking fields shared by all entities.
 * Using @Version gives us optimistic concurrency control out of the box,
 * which is how we resolve "two requests editing the same URL at once"
 * without resorting to pessimistic row locks for every read.
 *
 * Uses Lombok's @SuperBuilder (not plain @Builder) specifically so that
 * subclasses (User, Url) can build instances with these inherited fields
 * (createdAt/updatedAt/version) set directly via their own builder — a plain
 * @Builder on a subclass silently ignores superclass fields, which is a common
 * and easy-to-miss Lombok pitfall in JPA entity hierarchies.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
