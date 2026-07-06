package com.urlshortener.controller;

import com.urlshortener.dto.response.PagedResponse;
import com.urlshortener.dto.response.UserResponse;
import com.urlshortener.repository.AuditLogRepository;
import com.urlshortener.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * Admin-only surface. Coarse-grained access control (hasRole('ADMIN')) is enforced at
 * SecurityConfig's /api/v1/admin/** matcher AND restated here at the method level —
 * defense in depth, so this controller is provably safe even if the URL pattern in
 * SecurityConfig is ever refactored or a route is moved.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Administrative endpoints (ROLE_ADMIN required)")
public class AdminController {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    @GetMapping("/users")
    @Operation(summary = "List all users, paginated")
    public ResponseEntity<PagedResponse<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = userRepository.findAll(PageRequest.of(page, Math.min(size, 100)))
                .map(u -> new UserResponse(
                        u.getId().toString(), u.getEmail(), u.getUsername(), u.getFullName(),
                        u.isEnabled(),
                        u.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toSet()),
                        u.getCreatedAt()));
        return ResponseEntity.ok(PagedResponse.from(result));
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "View the global audit log, paginated, most recent first")
    public ResponseEntity<PagedResponse<com.urlshortener.domain.entity.AuditLog>> auditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size, 200)));
        return ResponseEntity.ok(PagedResponse.from(result));
    }
}
