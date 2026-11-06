package com.financetracker.security.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.financetracker.config.properties.AuthSettings;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.security.RateLimits;
import com.financetracker.security.token.CryptoTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Bounded, single-instance sliding windows. Never evicts a live bucket to admit an attacker.
 */
@Component
@RequiredArgsConstructor
public final class SlidingWindowRateLimits implements RateLimits {

    private final Map<String, RateLimitBucket> requests = new HashMap<>();
    private final Map<String, RateLimitBucket> passwords = new HashMap<>();
    private final Clock clock;
    private final AuthSettings settings;

    @Override
    public void request(String ip) {
        RateLimitBucket bucket = bucket(requests, ip, AuthPolicy.REQUEST_WINDOW);
        synchronized (bucket) {
            check(bucket, AuthPolicy.REQUEST_WINDOW, settings.requestsPerMinute());
            bucket.events.add(clock.instant());
        }
    }

    @Override
    public <T> T passwordAttempt(String username, String ip, Supplier<T> attempt) {
        RateLimitBucket bucket = bucket(passwords, CryptoTokens.hash(username + AuthPolicy.LOGIN_PAIR_SEPARATOR + ip), AuthPolicy.LOGIN_WINDOW);
        synchronized (bucket) {
            check(bucket, AuthPolicy.LOGIN_WINDOW, AuthPolicy.FAILED_LOGIN_LIMIT);

            try {
                return attempt.get();
            } catch (AuthFailure failure) {
                if (failure.status() == HttpStatus.UNAUTHORIZED.value()
                        || failure.status() == HttpStatus.FORBIDDEN.value()) {
                    bucket.events.add(clock.instant());
                }

                throw failure;
            }
        }
    }

    private RateLimitBucket bucket(
            Map<String, RateLimitBucket> registry, String key, Duration window) {
        synchronized (registry) {
            Instant now = clock.instant();
            var existing = registry.get(key);

            if (existing != null) {
                existing.touched = now;

                return existing;
            }

            if (registry.size() >= settings.maxRateLimitKeys()) {
                registry.values().removeIf(b -> b.touched.isBefore(now.minus(window)));

                if (registry.size() >= settings.maxRateLimitKeys()) {
                    throw AuthFailure.builder()
                            .status(HttpStatus.TOO_MANY_REQUESTS.value())
                            .code(ErrorCodes.RATE_LIMITED)
                            .retryAfter(AuthPolicy.CAPACITY_RETRY_SECONDS)
                            .build();
                }
            }

            var created = RateLimitBucket.builder().touched(now).build();
            registry.put(key, created);

            return created;
        }
    }

    private void check(RateLimitBucket bucket, Duration window, int limit) {
        Instant now = clock.instant();

        while (!bucket.events.isEmpty() && !bucket.events.peekFirst().plus(window).isAfter(now)) {
            bucket.events.removeFirst();
        }

        if (bucket.events.size() >= limit) {
            long seconds = Math.max(1, Duration.between(now, bucket.events.peekFirst().plus(window)).toSeconds() + 1);
            throw AuthFailure.builder()
                    .status(HttpStatus.TOO_MANY_REQUESTS.value())
                    .code(ErrorCodes.RATE_LIMITED)
                    .retryAfter(seconds)
                    .build();
        }
    }
}
