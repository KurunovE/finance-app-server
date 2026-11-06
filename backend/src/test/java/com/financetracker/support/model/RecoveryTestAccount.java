package com.financetracker.support.model;

import java.util.UUID;

import lombok.Builder;

@Builder
public record RecoveryTestAccount(String email, UUID device, String access) {
    @Override
    public String toString() {
        return "RecoveryTestAccount[redacted]";
    }
}
