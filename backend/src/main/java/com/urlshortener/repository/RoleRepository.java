package com.urlshortener.repository;

import com.urlshortener.domain.entity.Role;
import com.urlshortener.domain.enums.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Integer> {
    Optional<Role> findByName(RoleName name);
}
