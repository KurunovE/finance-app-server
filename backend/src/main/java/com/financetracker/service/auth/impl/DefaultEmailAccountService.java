package com.financetracker.service.auth.impl;

import java.time.Clock;
import java.util.UUID;

import com.financetracker.config.BeanNames;
import com.financetracker.config.properties.AccountMailSettings;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.repository.auth.AccountTokenRepository;
import com.financetracker.repository.auth.AuthRepository;
import com.financetracker.security.password.PasswordPolicy;
import com.financetracker.security.token.CryptoTokens;
import com.financetracker.service.auth.EmailAccountService;
import com.financetracker.service.mail.AccountMailer;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.support.EmailAddresses;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@RequiredArgsConstructor
public final class DefaultEmailAccountService implements EmailAccountService {
    private final RecoveryState recovery;
    private final AuthRepository users;
    private final AccountTokenRepository tokens;
    private final AccountMailer mail;
    private final PasswordPolicy passwords;
    private final AccountMailSettings settings;
    private final Clock clock;

    @Qualifier(BeanNames.WRITE_TRANSACTION)
    private final TransactionOperations transaction;

    @Override
    public void registration(UUID user, String email) {
        issue(user, email, AuthPolicy.VERIFY_EMAIL);
    }

    private void issue(UUID user, String email, String purpose) {
        String raw = CryptoTokens.random(AuthPolicy.TOKEN_BYTES);
        var now = clock.instant();
        tokens.replace(
            user,
            purpose,
            CryptoTokens.hash(raw),
            now,
            now.plus(
                purpose.equals(AuthPolicy.VERIFY_EMAIL)
                    ? settings.verificationTtl()
                    : settings.resetTtl()));
        mail.afterCommit(email, purpose, raw);
    }

    @Override
    public void requestVerification(String email) {
        request(email, AuthPolicy.VERIFY_EMAIL);
    }

    @Override
    public void forgotPassword(String email) {
        request(email, AuthPolicy.RESET_PASSWORD);
    }

    private void request(String email, String purpose) {
        var found = users.byEmail(EmailAddresses.normalize(email));
        // No SMTP or password work is awaited by the public request.

        if (found == null) {
            return;
        }

        transaction.executeWithoutResult(
            status -> {
                recovery.fence();
                var user = users.lockUser(found.id());

                if (user == null || !user.status().equals(StatusValues.ACTIVE)) {
                    return;
                }

                if (purpose.equals(AuthPolicy.VERIFY_EMAIL) && user.verified()) {
                    return;
                }

                if (purpose.equals(AuthPolicy.RESET_PASSWORD) && !user.verified()) {
                    return;
                }

                if (tokens.coolingDown(user.id(), purpose, clock.instant())) {
                    return;
                }

                issue(user.id(), user.email(), purpose);
            });
    }

    @Override
    public void verify(String raw) {
        String hash = hash(raw);
        UUID user = tokens.user(hash, AuthPolicy.VERIFY_EMAIL, clock.instant());

        if (user == null) {
            throw invalid();
        }

        transaction.executeWithoutResult(
            status -> {
                recovery.fence();
                var account = users.lockUser(user);

                if (account == null
                    || !account.status().equals(StatusValues.ACTIVE)
                    || !tokens.claim(
                    user, hash, AuthPolicy.VERIFY_EMAIL, clock.instant())) {
                    throw invalid();
                }

                tokens.verify(user, hash, clock.instant());
            });
    }

    @Override
    public void reset(String raw, String password) {
        String hash = hash(raw);
        UUID user = tokens.user(hash, AuthPolicy.RESET_PASSWORD, clock.instant());

        if (user == null) {
            throw invalid();
        }

        String encoded = passwords.encodeNew(password);
        transaction.executeWithoutResult(
            status -> {
                recovery.fence();
                var account = users.lockUser(user);

                if (account == null
                    || !account.status().equals(StatusValues.ACTIVE)
                    || !account.verified()
                    || !tokens.claim(
                    user, hash, AuthPolicy.RESET_PASSWORD, clock.instant())) {
                    throw invalid();
                }

                tokens.reset(user, encoded, clock.instant());
            });
    }

    private String hash(String raw) {
        if (!raw.matches(AuthPolicy.TOKEN_PATTERN)) {
            throw invalid();
        }

        return CryptoTokens.hash(raw);
    }

    private AuthFailure invalid() {
        return AuthFailure.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .code(ErrorCodes.INVALID_OR_EXPIRED_TOKEN)
            .build();
    }
}
