package com.urlshortener.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Extracts and validates the Bearer JWT on every request, then populates the
 * SecurityContext so downstream @PreAuthorize checks and controller code can
 * rely on Spring Security's normal authentication model. Invalid/missing tokens
 * simply leave the context empty — anonymous access decisions are then made by
 * SecurityConfig's authorizeHttpRequests rules, not by this filter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                if (jwtTokenProvider.isAccessToken(claims)) {
                    var userId = jwtTokenProvider.getUserId(claims);
                    var username = jwtTokenProvider.getUsername(claims);
                    List<GrantedAuthority> authorities = jwtTokenProvider.getRoles(claims).stream()
                            .map(SimpleGrantedAuthority::new)
                            .map(GrantedAuthority.class::cast)
                            .toList();

                    var authToken = new UsernamePasswordAuthenticationToken(
                            new AuthenticatedPrincipal(userId, username), null, authorities);
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    log.debug("Rejected non-access token presented to a protected endpoint.");
                }
            } catch (Exception ex) {
                // Deliberately swallow here: an invalid/expired token simply means the request
                // proceeds unauthenticated, and authorizeHttpRequests() decides whether that's allowed.
                log.debug("JWT validation failed: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
