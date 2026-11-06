package com.financetracker.model.dto.auth;

import com.financetracker.policy.AuthPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;

@Builder
public record EmailRequestDto(
        @NotBlank
        @Email
        @Size(max = AuthPolicy.MAX_EMAIL_LENGTH)
        String email
) {
    private static final String REDACTED_DESCRIPTION = "Email[redacted]";

    @NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
