package com.urlshortener.service.impl;

import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.entity.User;
import com.urlshortener.domain.enums.UrlStatus;
import com.urlshortener.dto.request.CreateUrlRequest;
import com.urlshortener.dto.response.UrlResponse;
import com.urlshortener.exception.DuplicateAliasException;
import com.urlshortener.exception.ResourceNotFoundException;
import com.urlshortener.exception.UrlDisabledException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.kafka.ClickEventProducer;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.security.AuthenticatedPrincipal;
import com.urlshortener.service.AuditLogService;
import com.urlshortener.service.CacheService;
import com.urlshortener.service.GeoLocationService;
import com.urlshortener.service.ShortCodeGeneratorService;
import com.urlshortener.util.UrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for UrlService — the heart of the business logic. Heavy use of Mockito
 * to isolate UrlService from its collaborators (repository, cache, Kafka), so these
 * tests run in milliseconds with no database/Redis/Kafka required, and specifically
 * target the edge cases called out in the project requirements: duplicate URLs, alias
 * collisions, expired/disabled link handling, and not-found semantics.
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock private UrlRepository urlRepository;
    @Mock private UserRepository userRepository;
    @Mock private ShortCodeGeneratorService codeGenerator;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CacheService cacheService;
    @Mock private ClickEventProducer clickEventProducer;
    @Mock private GeoLocationService geoLocationService;
    @Mock private AuditLogService auditLogService;
    @Mock private HttpServletRequest httpServletRequest;

    private UrlValidator urlValidator;

    @InjectMocks
    private UrlService urlService;

    private AuthenticatedPrincipal principal;
    private User owner;

    @BeforeEach
    void setUp() {
        urlValidator = new UrlValidator("http://localhost:8080");
        // UrlService gets its UrlValidator injected by Mockito's @InjectMocks via
        // constructor matching; since UrlValidator isn't mocked, wire it manually.
        ReflectionTestUtils.setField(urlService, "urlValidator", urlValidator);
        ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(urlService, "urlCacheTtlSeconds", 3600L);
        ReflectionTestUtils.setField(urlService, "negativeTtlSeconds", 60L);

        UUID userId = UUID.randomUUID();
        principal = new AuthenticatedPrincipal(userId, "alice");
        owner = User.builder().id(userId).username("alice").email("a@b.com").build();

        lenient().when(cacheService.get(anyString())).thenReturn(Optional.empty());
        lenient().when(cacheService.isNegative(any())).thenReturn(false);
        lenient().when(geoLocationService.resolve(anyString()))
                .thenReturn(new GeoLocationService.GeoLocation("XX", "Unknown"));
    }

    @Test
    void createGeneratesRandomCodeWhenNoCustomAliasProvided() {
        when(userRepository.findById(principal.id())).thenReturn(Optional.of(owner));
        when(urlRepository.findFirstByLongUrlHashAndOwnerIdAndStatusAndDeletedAtIsNull(anyString(), any(), any()))
                .thenReturn(Optional.empty());
        when(codeGenerator.generateUniqueCode()).thenReturn("aB3xK9q");
        when(urlRepository.save(any(Url.class))).thenAnswer(inv -> {
            Url u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());
            return u;
        });

        CreateUrlRequest request = new CreateUrlRequest("https://example.com/page", null, null, null, null, null, null);
        UrlResponse response = urlService.create(request, principal, "127.0.0.1");

        assertThat(response.shortCode()).isEqualTo("aB3xK9q");
        verify(codeGenerator).generateUniqueCode();
        verify(cacheService).put(eq("aB3xK9q"), any(), any());
    }

    @Test
    void createThrowsWhenCustomAliasAlreadyTaken() {
        when(userRepository.findById(principal.id())).thenReturn(Optional.of(owner));
        when(urlRepository.findFirstByLongUrlHashAndOwnerIdAndStatusAndDeletedAtIsNull(anyString(), any(), any()))
                .thenReturn(Optional.empty());
        when(urlRepository.existsByShortCodeAndDomainIsNullAndDeletedAtIsNull("my-alias")).thenReturn(true);

        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "my-alias", null, null, null, null, null);

        assertThatThrownBy(() -> urlService.create(request, principal, "127.0.0.1"))
                .isInstanceOf(DuplicateAliasException.class);
    }

    @Test
    void createReturnsExistingUrlForDuplicateLongUrlFromSameOwner() {
        Url existing = Url.builder()
                .id(UUID.randomUUID())
                .shortCode("existingCode")
                .longUrl("https://example.com/page")
                .status(UrlStatus.ACTIVE)
                .owner(owner)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(userRepository.findById(principal.id())).thenReturn(Optional.of(owner));
        when(urlRepository.findFirstByLongUrlHashAndOwnerIdAndStatusAndDeletedAtIsNull(anyString(), any(), any()))
                .thenReturn(Optional.of(existing));

        CreateUrlRequest request = new CreateUrlRequest("https://example.com/page", null, null, null, null, null, null);
        UrlResponse response = urlService.create(request, principal, "127.0.0.1");

        assertThat(response.shortCode()).isEqualTo("existingCode");
        // Should NOT generate a new code or save a new entity for a duplicate.
        verify(codeGenerator, never()).generateUniqueCode();
        verify(urlRepository, never()).save(any());
    }

    @Test
    void resolveAndTrackThrowsNotFoundForUnknownCode() {
        when(urlRepository.findByShortCodeAndDefaultDomain("doesNotExist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolveAndTrack("doesNotExist", httpServletRequest))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(cacheService).cacheNegative(eq("doesNotExist"), any());
    }

    @Test
    void resolveAndTrackThrowsForDisabledUrl() {
        Url disabled = Url.builder()
                .id(UUID.randomUUID())
                .shortCode("disabledCode")
                .longUrl("https://example.com")
                .status(UrlStatus.DISABLED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(urlRepository.findByShortCodeAndDefaultDomain("disabledCode")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> urlService.resolveAndTrack("disabledCode", httpServletRequest))
                .isInstanceOf(UrlDisabledException.class);
    }

    @Test
    void resolveAndTrackThrowsForExpiredUrl() {
        Url expired = Url.builder()
                .id(UUID.randomUUID())
                .shortCode("expiredCode")
                .longUrl("https://example.com")
                .status(UrlStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(60))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(urlRepository.findByShortCodeAndDefaultDomain("expiredCode")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> urlService.resolveAndTrack("expiredCode", httpServletRequest))
                .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    void resolveAndTrackPublishesClickEventAndReturnsLongUrlForActiveUrl() {
        Url active = Url.builder()
                .id(UUID.randomUUID())
                .shortCode("activeCode")
                .longUrl("https://example.com/target")
                .status(UrlStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(urlRepository.findByShortCodeAndDefaultDomain("activeCode")).thenReturn(Optional.of(active));
        when(httpServletRequest.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
        when(httpServletRequest.getRemoteAddr()).thenReturn("203.0.113.5");

        String result = urlService.resolveAndTrack("activeCode", httpServletRequest);

        assertThat(result).isEqualTo("https://example.com/target");
        verify(clickEventProducer).publish(any());
    }
}
