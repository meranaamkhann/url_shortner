package com.urlshortener.config;

import com.urlshortener.security.AccessDeniedHandlerImpl;
import com.urlshortener.security.AuthEntryPoint;
import com.urlshortener.security.JwtAuthenticationFilter;
import com.urlshortener.security.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security architecture summary (see docs/ARCHITECTURE.md for full rationale):
 *
 *  - Stateless sessions: every request is authenticated independently via the JWT
 *    in the Authorization header — no server-side session store, which is required
 *    for horizontal scaling behind a load balancer with no sticky sessions.
 *  - CSRF is disabled: CSRF protects cookie-based session auth from being riden by
 *    a malicious page. We don't use cookies for auth (Bearer tokens only, never
 *    auto-attached by the browser), so the CSRF threat model doesn't apply here.
 *    If a future web client switches to httpOnly cookies for tokens, CSRF protection
 *    (or SameSite=Strict + double-submit tokens) must be reinstated at that point.
 *  - RBAC: method-level @PreAuthorize is used in controllers/services for fine-grained
 *    ownership checks (e.g. "only the URL's owner or an admin can edit it"), while
 *    coarse role gates (e.g. /api/admin/** requires ROLE_ADMIN) live here.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final AuthEntryPoint authEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt with strength 12: deliberately more expensive than the default (10)
        // to raise the cost of offline brute-forcing a leaked password hash table,
        // while staying fast enough (~250ms) not to bottleneck login throughput.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config, UserDetailsService userDetailsService, PasswordEncoder passwordEncoder)
            throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new org.springframework.security.authentication.ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable()) // see class javadoc: not applicable to stateless Bearer-token auth
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(eh -> eh
                    .authenticationEntryPoint(authEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
            .headers(headers -> headers
                    .contentTypeOptions(opts -> {})                              // X-Content-Type-Options: nosniff
                    .frameOptions(opts -> opts.deny())                           // X-Frame-Options: DENY (clickjacking)
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000))                          // Strict-Transport-Security
                    .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; frame-ancestors 'none'; object-src 'none'"))
                    // permissionsPolicy() is called last in this chain deliberately: its
                    // Customizer overload returns its own nested PermissionsPolicyConfig
                    // rather than HeadersConfigurer, so nothing can be chained after it.
                    .permissionsPolicy(pp -> pp.policy("geolocation=(), microphone=(), camera=()")))
            .authorizeHttpRequests(auth -> auth
                    // Public, unauthenticated endpoints
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/r/**").permitAll()                       // the redirect hot path
                    .requestMatchers("/api/v1/public/**").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // Admin-only surface
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    // Everything else requires authentication; per-resource ownership is
                    // enforced with @PreAuthorize at the service/controller method level.
                    .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // In production this list comes from an environment variable / config map per
        // deployment environment — never "*" once credentials/Authorization headers are involved.
        config.setAllowedOriginPatterns(List.of("https://*.yourdomain.com", "http://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}