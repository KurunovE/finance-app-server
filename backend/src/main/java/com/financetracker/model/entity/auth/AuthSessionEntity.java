package com.financetracker.model.entity.auth;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;

@Builder
public record AuthSessionEntity(
        UUID family,
        UUID user,
        UUID device,
        UUID jti,
        String refreshHash,
        Instant refreshExpiry,
        boolean revoked
) {
    private static final String REDACTED_DESCRIPTION = "Session[redacted]";

    @NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
