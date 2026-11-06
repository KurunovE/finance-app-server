package com.financetracker.security.token;

import java.util.UUID;

import com.financetracker.model.dto.auth.AccessIdentityDto;

public interface JwtTokens {
    String issue(UUID user, UUID device, UUID jti, UUID epoch);

    AccessIdentityDto verify(String token);
}
