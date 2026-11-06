package com.financetracker.model.dto.auth;

import java.util.UUID;

import com.financetracker.policy.AuthPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record LoginRequestDto(
        @NotBlank
        @Email
        @Size(max = AuthPolicy.MAX_EMAIL_LENGTH)
        String email,

        @NotNull
        @Size(max = AuthPolicy.MAX_PASSWORD_INPUT_LENGTH)
        String password,

        @NotNull UUID deviceId
) {
    private static final String REDACTED_DESCRIPTION = "Login[redacted]";

    @org.jetbrains.annotations.NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
