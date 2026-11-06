package com.financetracker.model.dto.auth;

import com.financetracker.policy.AuthPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;

@Builder
public record RefreshRequestDto(
        @NotBlank
        @Size(max = AuthPolicy.MAX_TOKEN_INPUT_LENGTH)
        String refreshToken
) {
    private static final String REDACTED_DESCRIPTION = "Refresh[redacted]";

    @NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
