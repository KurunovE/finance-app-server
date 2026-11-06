package com.financetracker.service.auth;

import java.util.UUID;

public interface EmailAccountService {
    void registration(UUID user, String email);

    void requestVerification(String email);

    void forgotPassword(String email);

    void verify(String raw);

    void reset(String raw, String password);
}
