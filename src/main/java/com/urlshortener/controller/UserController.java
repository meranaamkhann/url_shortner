package com.urlshortener.controller;

import com.urlshortener.dto.request.ChangePasswordRequest;
import com.urlshortener.dto.response.UserProfileResponse;
import com.urlshortener.security.AuthenticatedPrincipal;
import com.urlshortener.service.impl.UserService;
import com.urlshortener.util.IpAddressUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Account", description = "Profile and account management for the signed-in user")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "Get the current user's profile")
    public ResponseEntity<UserProfileResponse> me(Authentication authentication) {
        var principal = (AuthenticatedPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(userService.getProfile(principal.id()));
    }

    @PatchMapping("/me/password")
    @Operation(summary = "Change the current user's password (revokes all other sessions on success)")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                Authentication authentication,
                                                HttpServletRequest httpRequest) {
        var principal = (AuthenticatedPrincipal) authentication.getPrincipal();
        userService.changePassword(principal.id(), request, IpAddressUtil.resolveClientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
