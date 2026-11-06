package com.financetracker.repository.auth;

import java.time.Instant;
import java.util.UUID;

public interface AccountTokenRepository {
    UUID user(String hash, String purpose, Instant now);

    boolean coolingDown(UUID user, String purpose, Instant now);

    void replace(UUID user, String purpose, String hash, Instant now, Instant expires);

    boolean claim(UUID user, String hash, String purpose, Instant now);

    void verify(UUID user, String hash, Instant now);

    void reset(UUID user, String encoded, Instant now);
}
