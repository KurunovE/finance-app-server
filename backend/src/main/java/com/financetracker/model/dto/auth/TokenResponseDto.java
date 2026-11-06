package com.financetracker.model.dto.auth;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;

@Builder
public record TokenResponseDto(
    String accessToken,
    String refreshToken,
    String tokenType,
    int expiresIn
) {
    private static final String REDACTED_DESCRIPTION = "Tokens[redacted]";

    @NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
