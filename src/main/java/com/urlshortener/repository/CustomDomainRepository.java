package com.urlshortener.repository;

import com.urlshortener.domain.entity.CustomDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomDomainRepository extends JpaRepository<CustomDomain, UUID> {

    Optional<CustomDomain> findByDomain(String domain);

    List<CustomDomain> findByOwnerId(UUID ownerId);

    boolean existsByDomain(String domain);
}
