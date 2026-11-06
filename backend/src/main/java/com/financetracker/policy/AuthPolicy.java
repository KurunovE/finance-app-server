package com.financetracker.policy;

import java.time.Duration;
import java.util.Set;

import lombok.experimental.UtilityClass;

@UtilityClass
public class AuthPolicy {
    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 128;
    public static final int MAX_PASSWORD_INPUT_LENGTH = 256;
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MAX_TOKEN_INPUT_LENGTH = 128;
    public static final int TOKEN_BYTES = 32;
    public static final int INVITE_BYTES = 16;
    public static final int MAX_KEY_BYTES = 4096;
    public static final int MAX_JWT_LENGTH = 8192;
    public static final int ACCESS_TTL_SECONDS = 900;
    public static final Duration REFRESH_TTL = Duration.ofDays(60);
    public static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    public static final Duration REQUEST_WINDOW = Duration.ofMinutes(1);
    public static final int FAILED_LOGIN_LIMIT = 5;
    public static final int RESEND_COOLDOWN_SECONDS = 60;
    public static final int CAPACITY_RETRY_SECONDS = 60;
    public static final int ARGON_SALT_BYTES = 16;
    public static final int ARGON_HASH_BYTES = 32;
    public static final int ARGON_PARALLELISM = 1;
    public static final int ARGON_MEMORY_KIB = 19456;
    public static final int ARGON_ITERATIONS = 2;
    public static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";
    public static final String INVITE_PATTERN = "[A-Za-z0-9_-]{22}";
    public static final String PASSWORD_ENCODER_ID = "argon2id";
    public static final String COMMON_PASSWORDS_RESOURCE = "security/common-passwords.txt";
    public static final Duration DEFAULT_INVITE_TTL = Duration.ofDays(7);
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String LOGIN_PAIR_SEPARATOR = "\n";
    public static final String BEARER_SCHEME = "Bearer";
    public static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    public static final String RESET_PASSWORD = "RESET_PASSWORD";
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("RUB", "EUR", "USD");
    public static final Duration MAX_VERIFICATION_TTL = Duration.ofDays(7);
    public static final Duration MAX_RESET_TTL = Duration.ofHours(1);
}
