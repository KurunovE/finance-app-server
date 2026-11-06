package com.financetracker.controller.auth;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.DeleteAccountRequestDto;
import com.financetracker.model.dto.auth.LoginRequestDto;
import com.financetracker.model.dto.auth.ProfileResponseDto;
import com.financetracker.model.dto.auth.RefreshRequestDto;
import com.financetracker.model.dto.auth.RegisterRequestDto;
import com.financetracker.model.dto.auth.TokenResponseDto;
import com.financetracker.security.RateLimits;
import com.financetracker.service.auth.AuthService;
import com.financetracker.support.EmailAddresses;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public final class AuthController implements AuthControllerApi {

    private final AuthService auth;
    private final RateLimits limits;

    @Override
    public ResponseEntity<Void> register(RegisterRequestDto request) {
        auth.register(
            request.email(),
            request.password(),
            request.baseCurrencyCode(),
            request.deviceId(),
            request.inviteCode());

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Override
    public ResponseEntity<TokenResponseDto> login(LoginRequestDto request, HttpServletRequest http) {
        return ResponseEntity.ok(
            limits.passwordAttempt(
                EmailAddresses.normalize(request.email()),
                http.getRemoteAddr(),
                () -> auth.login(request.email(), request.password(), request.deviceId())));
    }

    @Override
    public ResponseEntity<TokenResponseDto> refresh(RefreshRequestDto request) {
        return ResponseEntity.ok(auth.refresh(request.refreshToken()));
    }

    @Override
    public ResponseEntity<ProfileResponseDto> me(AccessIdentityDto identity) {
        return ResponseEntity.ok(auth.me(identity));
    }

    @Override
    public ResponseEntity<Void> logout(AccessIdentityDto identity) {
        auth.logout(identity);

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> delete(AccessIdentityDto identity, DeleteAccountRequestDto request, HttpServletRequest http) {
        limits.passwordAttempt(
            "delete:" + identity.userId(),
            http.getRemoteAddr(),
            () -> {
                auth.delete(identity, request.password());
                return null;
            });

        return ResponseEntity.noContent().build();
    }
}
