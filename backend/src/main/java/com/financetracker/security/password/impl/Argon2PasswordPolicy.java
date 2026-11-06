package com.financetracker.security.password.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.security.password.PasswordPolicy;
import com.financetracker.security.token.CryptoTokens;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class Argon2PasswordPolicy implements PasswordPolicy {
    private final PasswordEncoder encoder;
    private final Set<String> common;
    private final String dummyHash;

    public Argon2PasswordPolicy(PasswordEncoder encoder) throws IOException {
        this.encoder = encoder;

        try (var reader = new BufferedReader(new InputStreamReader(new ClassPathResource(AuthPolicy.COMMON_PASSWORDS_RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            common =
                    reader.lines()
                            .filter(line -> !line.isBlank())
                            .collect(Collectors.toUnmodifiableSet());
        }

        dummyHash = encoder.encode(CryptoTokens.random(AuthPolicy.TOKEN_BYTES));
    }

    @Override
    public String encodeNew(String password) {
        int length = password.codePointCount(0, password.length());

        if (length < AuthPolicy.MIN_PASSWORD_LENGTH
                || length > AuthPolicy.MAX_PASSWORD_LENGTH
                || common.contains(password.toLowerCase(Locale.ROOT))) {
            throw AuthFailure.builder()
                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                    .code(ErrorCodes.PASSWORD_REJECTED)
                    .build();
        }

        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String stored) {
        return encoder.matches(password, stored == null ? dummyHash : stored) && stored != null;
    }

    @Override
    public boolean needsUpgrade(String stored) {
        return encoder.upgradeEncoding(stored);
    }

    @Override
    public String upgrade(String password) {
        return encoder.encode(password);
    }
}
