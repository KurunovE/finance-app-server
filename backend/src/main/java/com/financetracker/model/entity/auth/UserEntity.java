package com.financetracker.model.entity.auth;

import java.util.UUID;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;

@Builder
public record UserEntity(
        UUID id,
        String email,
        String normalizedEmail,
        String hash,
        String currency,
        String status,
        boolean verified
) {
    private static final String REDACTED_DESCRIPTION = "User[redacted]";

    @NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
