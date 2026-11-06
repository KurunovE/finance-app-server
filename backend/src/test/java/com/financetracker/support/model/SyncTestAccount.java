package com.financetracker.support.model;

import java.util.UUID;

import lombok.Builder;

@Builder
public record SyncTestAccount(UUID user, String access, UUID category) {
    @Override
    public String toString() {
        return "SyncTestAccount[redacted]";
    }
}
