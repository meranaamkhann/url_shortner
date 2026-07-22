package com.urlshortener.service.impl;

import com.urlshortener.domain.entity.Role;
import com.urlshortener.domain.entity.User;
import com.urlshortener.domain.enums.RoleName;
import com.urlshortener.dto.request.ChangePasswordRequest;
import com.urlshortener.dto.response.UserProfileResponse;
import com.urlshortener.exception.InvalidCredentialsException;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.service.AuditLogService;
import com.urlshortener.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuditLogService auditLogService;

    private UserService userService;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, refreshTokenService, auditLogService);
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId)
                .username("asad")
                .email("asad@example.com")
                .fullName("Asad Khan")
                .passwordHash("old-hash")
                .enabled(true)
                .roles(Set.of(Role.builder().name(RoleName.ROLE_USER).build()))
                .build();
    }

    @Test
    void changePasswordUpdatesHashAndRevokesOtherSessionsWhenCurrentPasswordCorrect() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPass1", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");

        userService.changePassword(userId, new ChangePasswordRequest("oldPass1", "NewPass123"), "203.0.113.5");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
        verify(refreshTokenService).revokeAllForUser(userId);
        verify(auditLogService).log(eq(userId), any(), eq("User"), eq(userId.toString()), eq("203.0.113.5"), any());
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordWithoutTouchingAnything() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPass", "old-hash")).thenReturn(false);

        assertThatThrownBy(() ->
                userService.changePassword(userId, new ChangePasswordRequest("wrongPass", "NewPass123"), "203.0.113.5"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void changePasswordThrowsWhenUserNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                userService.changePassword(userId, new ChangePasswordRequest("oldPass1", "NewPass123"), "203.0.113.5"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getProfileMapsEntityFieldsCorrectly() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserProfileResponse profile = userService.getProfile(userId);

        assertThat(profile.id()).isEqualTo(userId.toString());
        assertThat(profile.username()).isEqualTo("asad");
        assertThat(profile.email()).isEqualTo("asad@example.com");
        assertThat(profile.fullName()).isEqualTo("Asad Khan");
        assertThat(profile.roles()).containsExactly("ROLE_USER");
    }

    @Test
    void getProfileThrowsWhenUserNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getProfile(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
