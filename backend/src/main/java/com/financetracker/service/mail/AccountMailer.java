package com.financetracker.service.mail;

public interface AccountMailer {
    void afterCommit(String recipient, String purpose, String token);
}
