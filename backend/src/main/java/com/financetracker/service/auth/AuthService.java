package com.financetracker.service.auth;

import java.util.UUID;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.ProfileResponseDto;
import com.financetracker.model.dto.auth.TokenResponseDto;

public interface AuthService {
    void register(String email, String password, String currency, UUID device, String invite);

    TokenResponseDto login(String email, String password, UUID device);

    TokenResponseDto refresh(String refresh);

    AccessIdentityDto authenticate(String access);

    ProfileResponseDto me(AccessIdentityDto identity);

    void logout(AccessIdentityDto identity);

    void delete(AccessIdentityDto identity, String password);
}
