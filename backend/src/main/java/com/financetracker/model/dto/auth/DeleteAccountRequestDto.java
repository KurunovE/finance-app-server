package com.financetracker.model.dto.auth;

import com.financetracker.policy.AuthPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record DeleteAccountRequestDto(
        @NotNull
        @Size(max = AuthPolicy.MAX_PASSWORD_INPUT_LENGTH)
        String password
) {
    private static final String REDACTED_DESCRIPTION = "Delete[redacted]";

    @org.jetbrains.annotations.NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
