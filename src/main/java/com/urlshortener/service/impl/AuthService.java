package com.urlshortener.service.impl;

import com.urlshortener.domain.entity.User;
import com.urlshortener.domain.enums.AuditAction;
import com.urlshortener.domain.enums.RoleName;
import com.urlshortener.dto.request.LoginRequest;
import com.urlshortener.dto.request.RefreshTokenRequest;
import com.urlshortener.dto.request.RegisterRequest;
import com.urlshortener.dto.response.AuthResponse;
import com.urlshortener.exception.DuplicateUserException;
import com.urlshortener.exception.InvalidCredentialsException;
import com.urlshortener.repository.RoleRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.security.JwtTokenProvider;
import com.urlshortener.service.AuditLogService;
import com.urlshortener.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateUserException("An account with email '" + request.email() + "' already exists.");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUserException("Username '" + request.username() + "' is already taken.");
        }

        var userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not seeded in the database."));

        User user = User.builder()
                .email(request.email())
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .enabled(true)
                .accountLocked(false)
                .roles(Set.of(userRole))
                .build();
        user = userRepository.save(user);

        auditLogService.log(user.getId(), AuditAction.USER_REGISTERED, "User", user.getId().toString(),
                ipAddress, Map.of("email", user.getEmail()));

        return buildAuthResponse(user, null, null);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent, String ipAddress) {
        User user = userRepository.findByUsername(request.usernameOrEmail())
                .or(() -> userRepository.findByEmail(request.usernameOrEmail()))
                .orElseThrow(() -> new InvalidCredentialsException("The email or password you entered is incorrect."));

        if (user.isAccountLocked()) {
            auditLogService.log(user.getId(), AuditAction.LOGIN_FAILED, "User", user.getId().toString(),
                    ipAddress, Map.of("reason", "ACCOUNT_LOCKED"));
            throw new InvalidCredentialsException("This account has been locked. Please contact support.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int failures = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(failures);
            // Lock the account after 10 consecutive failures — standard brute-force mitigation.
            if (failures >= 10) {
                user.setAccountLocked(true);
                log.warn("Account locked due to {} consecutive failed login attempts: userId={}", failures, user.getId());
            }
            userRepository.save(user);
            auditLogService.log(user.getId(), AuditAction.LOGIN_FAILED, "User", user.getId().toString(),
                    ipAddress, Map.of("failedCount", failures));
            throw new InvalidCredentialsException("The email or password you entered is incorrect.");
        }

        // Reset failure count on successful login
        user.setFailedLoginCount(0);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        String rawRefreshToken = refreshTokenService.issue(user, userAgent, ipAddress);
        auditLogService.log(user.getId(), AuditAction.LOGIN_SUCCESS, "User", user.getId().toString(),
                ipAddress, Map.of());
        return buildAuthResponse(user, rawRefreshToken, userAgent);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress) {
        var result = refreshTokenService.rotate(request.refreshToken(), userAgent, ipAddress);
        auditLogService.log(result.user().getId(), AuditAction.TOKEN_REFRESHED, "User",
                result.user().getId().toString(), ipAddress, Map.of());
        return buildAuthResponse(result.user(), result.newRefreshToken(), userAgent);
    }

    @Transactional
    public void logout(String userId, String ipAddress) {
        try {
            java.util.UUID id = java.util.UUID.fromString(userId);
            refreshTokenService.revokeAllForUser(id);
            auditLogService.log(id, AuditAction.LOGOUT, "User", userId, ipAddress, Map.of());
        } catch (IllegalArgumentException e) {
            // already invalid — nothing to revoke
        }
    }

    private AuthResponse buildAuthResponse(User user, String rawRefreshToken, String userAgent) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .collect(Collectors.toList());

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), roles);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                "Bearer",
                jwtTokenProvider.getAccessTokenExpirationSeconds(),
                new AuthResponse.UserSummary(
                        user.getId().toString(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toSet())
                )
        );
    }
}
