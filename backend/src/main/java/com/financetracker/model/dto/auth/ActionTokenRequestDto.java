package com.financetracker.model.dto.auth;

import com.financetracker.policy.AuthPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;

@Builder
public record ActionTokenRequestDto(
    @NotBlank
    @Size(max = AuthPolicy.MAX_TOKEN_INPUT_LENGTH)
    String token
) {
    private static final String REDACTED_DESCRIPTION = "Token[redacted]";

    @NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
