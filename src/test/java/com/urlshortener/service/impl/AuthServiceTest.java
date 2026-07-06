package com.urlshortener.service.impl;

import com.urlshortener.domain.entity.Role;
import com.urlshortener.domain.entity.User;
import com.urlshortener.domain.enums.RoleName;
import com.urlshortener.dto.request.LoginRequest;
import com.urlshortener.dto.request.RegisterRequest;
import com.urlshortener.dto.response.AuthResponse;
import com.urlshortener.exception.DuplicateUserException;
import com.urlshortener.exception.InvalidCredentialsException;
import com.urlshortener.repository.RoleRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.security.JwtTokenProvider;
import com.urlshortener.service.AuditLogService;
import com.urlshortener.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private AuthService authService;

    private Role userRole;

    @BeforeEach
    void setUp() {
        userRole = Role.builder().id(1).name(RoleName.ROLE_USER).build();
    }

    @Test
    void registerCreatesUserWithEncodedPasswordAndDefaultRole() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(roleRepository.findByName(RoleName.ROLE_USER)).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("Password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(jwtTokenProvider.generateAccessToken(any(), any(), anyList())).thenReturn("access-token");
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(900L);

        RegisterRequest request = new RegisterRequest("alice@example.com", "alice", "Password123", "Alice A.");
        AuthResponse response = authService.register(request, "127.0.0.1");

        assertThat(response.user().username()).isEqualTo("alice");
        assertThat(response.accessToken()).isEqualTo("access-token");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed-password");
        assertThat(userCaptor.getValue().getRoles()).contains(userRole);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("alice@example.com", "alice", "Password123", null);

        assertThatThrownBy(() -> authService.register(request, "127.0.0.1"))
                .isInstanceOf(DuplicateUserException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("alice@example.com", "alice", "Password123", null);

        assertThatThrownBy(() -> authService.register(request, "127.0.0.1"))
                .isInstanceOf(DuplicateUserException.class);
    }

    @Test
    void loginSucceedsWithCorrectCredentialsAndResetsFailedCount() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .email("alice@example.com")
                .passwordHash("hashed-password")
                .failedLoginCount(3)
                .accountLocked(false)
                .roles(Set.of(userRole))
                .build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", "hashed-password")).thenReturn(true);
        when(refreshTokenService.issue(eq(user), any(), any())).thenReturn("refresh-token");
        when(jwtTokenProvider.generateAccessToken(any(), any(), anyList())).thenReturn("access-token");
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(900L);

        LoginRequest request = new LoginRequest("alice", "Password123");
        AuthResponse response = authService.login(request, "test-agent", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    void loginFailsWithIncorrectPasswordAndIncrementsFailedCount() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .passwordHash("hashed-password")
                .failedLoginCount(0)
                .accountLocked(false)
                .roles(Set.of(userRole))
                .build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPassword", "hashed-password")).thenReturn(false);

        LoginRequest request = new LoginRequest("alice", "WrongPassword");

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        assertThat(user.isAccountLocked()).isFalse();
    }

    @Test
    void accountLocksAfterTenFailedAttempts_bruteForceProtection() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .passwordHash("hashed-password")
                .failedLoginCount(9) // one more failure will trip the lock threshold
                .accountLocked(false)
                .roles(Set.of(userRole))
                .build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        LoginRequest request = new LoginRequest("alice", "WrongPassword");

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.isAccountLocked()).isTrue();
    }

    @Test
    void loginRejectsLockedAccountEvenWithCorrectPassword() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .passwordHash("hashed-password")
                .accountLocked(true)
                .roles(Set.of(userRole))
                .build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("alice", "Password123");

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        // A locked account should fail BEFORE a password comparison ever happens.
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void loginRejectsUnknownUsernameWithoutLeakingWhichFieldWasWrong() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("ghost", "whatever");

        assertThatThrownBy(() -> authService.login(request, "test-agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageNotContaining("username")
                .hasMessageNotContaining("does not exist");
    }
}
