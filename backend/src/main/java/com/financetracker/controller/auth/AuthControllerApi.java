package com.financetracker.controller.auth;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.DeleteAccountRequestDto;
import com.financetracker.model.dto.auth.LoginRequestDto;
import com.financetracker.model.dto.auth.ProfileResponseDto;
import com.financetracker.model.dto.auth.RefreshRequestDto;
import com.financetracker.model.dto.auth.RegisterRequestDto;
import com.financetracker.model.dto.auth.TokenResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

public interface AuthControllerApi {

    @PostMapping("/api/v1/auth/register")
    ResponseEntity<Void> register(@Valid @RequestBody RegisterRequestDto request);

    @PostMapping("/api/v1/auth/login")
    ResponseEntity<TokenResponseDto> login(
            @Valid @RequestBody LoginRequestDto request, HttpServletRequest http);

    @PostMapping("/api/v1/auth/refresh")
    ResponseEntity<TokenResponseDto> refresh(@Valid @RequestBody RefreshRequestDto request);

    @GetMapping("/api/v1/me")
    ResponseEntity<ProfileResponseDto> me(@AuthenticationPrincipal AccessIdentityDto identity);

    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AccessIdentityDto identity);

    @DeleteMapping("/v1/me")
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal AccessIdentityDto identity,
            @Valid @RequestBody DeleteAccountRequestDto request,
            HttpServletRequest http);
}
