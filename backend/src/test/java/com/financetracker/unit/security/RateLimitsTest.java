package com.financetracker.unit.security;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.financetracker.config.properties.AuthSettings;
import com.financetracker.exception.AuthFailure;
import com.financetracker.security.impl.SlidingWindowRateLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class RateLimitsTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    @Mock Clock clock;
    @Mock AuthSettings settings;
    @InjectMocks SlidingWindowRateLimits limits;

    @BeforeEach
    void setup() {
        Mockito.when(clock.instant()).thenReturn(NOW);
    }

    @Test
    @DisplayName("Лимит запросов истекает без вытеснения активных IP")
    void requestWindowExpiresAndLiveBucketsCannotBeEvicted() {
        Mockito.when(settings.requestsPerMinute()).thenReturn(2);
        Mockito.when(settings.maxRateLimitKeys()).thenReturn(2);
        limits.request("a");
        limits.request("a");
        assertEquals(429, assertThrows(AuthFailure.class, () -> limits.request("a")).status());
        limits.request("b");
        assertThrows(AuthFailure.class, () -> limits.request("c"));
        Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        limits.request("c");
        limits.request("a");
    }

    @Test
    @DisplayName("Лимит входа изолирован по email и IP")
    void loginLimitCountsFailuresPerPairAndResetsAfterWindow() {
        Mockito.when(settings.maxRateLimitKeys()).thenReturn(100);

        for (int i = 0; i < 5; i++) {
            assertEquals(
                    401,
                    assertThrows(
                                    AuthFailure.class,
                                    () ->
                                            limits.passwordAttempt(
                                                    "user",
                                                    "a",
                                                    () -> {
                                                        throw AuthFailure.unauthorized();
                                                    }))
                            .status());
        }

        var failure = assertThrows(AuthFailure.class, () -> limits.passwordAttempt("user", "a", () -> true));
        assertEquals(429, failure.status());
        assertTrue(failure.retryAfter() > 0);
        assertTrue(limits.passwordAttempt("user", "b", () -> true));
        assertTrue(limits.passwordAttempt("other", "a", () -> true));
        assertThrows(
                AuthFailure.class,
                () ->
                        limits.passwordAttempt(
                                "bad",
                                "a",
                                () -> {
                                    throw AuthFailure.builder().status(422).code("INVALID").build();
                                }));
        Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(901));
        assertTrue(limits.passwordAttempt("user", "a", () -> true));
    }

    @Test
    @DisplayName("Параллельный вход допускает пять ошибок")
    void concurrentAttemptsCannotExceedFiveFailedChecks() throws Exception {
        Mockito.when(settings.maxRateLimitKeys()).thenReturn(100);

        try (var executor = Executors.newFixedThreadPool(10)) {
            var jobs = new ArrayList<Callable<Integer>>();

            for (int i = 0; i < 20; i++) {
                jobs.add(
                        () -> {
                            try {
                                return limits.passwordAttempt(
                                        "same",
                                        "ip",
                                        () -> {
                                            throw AuthFailure.unauthorized();
                                        });
                            } catch (AuthFailure failure) {
                                return failure.status();
                            }
                        });
            }

            var results = executor.invokeAll(jobs);
            int unauthorized = 0, throttled = 0;

            for (var result : results) {
                int status = result.get();

                if (status == 401) {
                    unauthorized++;
                }

                if (status == 429) {
                    throttled++;
                }
            }

            assertEquals(5, unauthorized);
            assertEquals(15, throttled);
        }
    }
}
