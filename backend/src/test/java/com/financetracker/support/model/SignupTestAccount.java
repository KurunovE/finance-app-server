package com.financetracker.support.model;

import java.util.UUID;

import lombok.Builder;

@Builder
public record SignupTestAccount(String email, UUID device, String verifyToken) {
    @Override
    public String toString() {
        return "SignupTestAccount[redacted]";
    }
}
