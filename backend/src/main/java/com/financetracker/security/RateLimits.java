package com.financetracker.security;

import java.util.function.Supplier;

public interface RateLimits {
    void request(String ip);

    <T> T passwordAttempt(String username, String ip, Supplier<T> attempt);
}
