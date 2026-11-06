package com.financetracker.support.model;

import java.util.UUID;

import lombok.Builder;

@Builder
public record AuthTestAccount(
        String email, UUID device, String invite, String access, String refresh, UUID user) {
    @Override
    public String toString() {
        return "AuthTestAccount[redacted]";
    }
}
