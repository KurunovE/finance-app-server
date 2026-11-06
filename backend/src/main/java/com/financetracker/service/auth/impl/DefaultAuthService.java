package com.financetracker.service.auth.impl;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.financetracker.config.BeanNames;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.ProfileResponseDto;
import com.financetracker.model.dto.auth.TokenResponseDto;
import com.financetracker.model.entity.auth.UserEntity;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.repository.auth.AuthRepository;
import com.financetracker.repository.context.UserTransactionContext;
import com.financetracker.security.password.PasswordPolicy;
import com.financetracker.security.token.CryptoTokens;
import com.financetracker.security.token.JwtTokens;
import com.financetracker.service.auth.AuthService;
import com.financetracker.service.auth.EmailAccountService;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.support.EmailAddresses;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@RequiredArgsConstructor
public final class DefaultAuthService implements AuthService {

    private final AuthRepository repository;
    private final RecoveryState recovery;
    private final PasswordPolicy passwords;
    private final JwtTokens jwt;
    private final UserTransactionContext context;

    @Qualifier(BeanNames.WRITE_TRANSACTION)
    private final TransactionOperations transaction;

    private final Clock clock;
    private final EmailAccountService emailAccounts;

    @Override
    public void register(
            String email, String password, String currency, UUID device, String invite) {
        String normalized = EmailAddresses.normalize(email);

        if (!AuthPolicy.SUPPORTED_CURRENCIES.contains(currency)) {
            throw AuthFailure.builder()
                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                    .code(ErrorCodes.INVALID_CURRENCY)
                    .build();
        }

        if (!invite.matches(AuthPolicy.INVITE_PATTERN)) {
            throw AuthFailure.builder()
                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                    .code(ErrorCodes.INVITE_INVALID)
                    .build();
        }

        String inviteHash = CryptoTokens.hash(invite);

        if (!repository.availableInvite(inviteHash, clock.instant())) {
            throw AuthFailure.builder()
                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                    .code(ErrorCodes.INVITE_INVALID)
                    .build();
        }

        String hash = passwords.encodeNew(password);

        try {
            transaction.executeWithoutResult(
                    status -> {
                        recovery.fence();

                        if (!repository.lockInvite(inviteHash, clock.instant())) {
                            throw AuthFailure.builder()
                                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                                    .code(ErrorCodes.INVITE_INVALID)
                                    .build();
                        }

                        UUID user = UUID.randomUUID();
                        repository.insertUser(user, email.strip(), normalized, hash, currency);
                        repository.consumeInvite(inviteHash, user, clock.instant());
                        context.setUser(user);
                        repository.initialCategories(user);
                        repository.insertDevice(user, device);
                        emailAccounts.registration(user, email.strip());
                    });
        } catch (DuplicateKeyException ignored) {
            throw AuthFailure.builder()
                    .status(HttpStatus.CONFLICT.value())
                    .code(ErrorCodes.REGISTRATION_REJECTED)
                    .build();
        }
    }

    @Override
    public TokenResponseDto login(String email, String password, UUID device) {
        var candidate = repository.byEmail(EmailAddresses.normalize(email));
        boolean matches = passwords.matches(password, candidate == null ? null : candidate.hash());

        if (!matches
                || candidate == null
                || !candidate.status().equals(StatusValues.ACTIVE)
                || !candidate.verified()) {
            throw AuthFailure.unauthorized();
        }

        String replacement = passwords.needsUpgrade(candidate.hash()) ? passwords.upgrade(password) : null;

        return transaction.execute(
                status -> {
                    recovery.fence();
                    var current = repository.lockUser(candidate.id());

                    if (current == null
                            || !current.status().equals(StatusValues.ACTIVE)
                            || !current.verified()
                            || !Objects.equals(current.hash(), candidate.hash())) {
                        throw AuthFailure.unauthorized();
                    }

                    if (replacement != null) {
                        repository.upgradePassword(current.id(), replacement);
                    }

                    return newSession(current.id(), device);
                });
    }

    private TokenResponseDto newSession(UUID user, UUID device) {
        String refresh = CryptoTokens.random(AuthPolicy.TOKEN_BYTES);
        UUID jti = UUID.randomUUID();
        repository.insertSession(
                user, device, UUID.randomUUID(), jti, CryptoTokens.hash(refresh), clock.instant());

        return TokenResponseDto.builder()
                .accessToken(jwt.issue(user, device, jti, recovery.current().epoch()))
                .refreshToken(refresh)
                .tokenType(AuthPolicy.BEARER_SCHEME)
                .expiresIn(AuthPolicy.ACCESS_TTL_SECONDS)
                .build();
    }

    @Override
    public TokenResponseDto refresh(String refresh) {
        if (!refresh.matches(AuthPolicy.TOKEN_PATTERN)) {
            throw AuthFailure.unauthorized();
        }

        String hash = CryptoTokens.hash(refresh);
        var reference = repository.reference(hash);

        if (reference == null) {
            throw AuthFailure.unauthorized();
        }

        TokenResponseDto result = transaction.execute(status -> {
            recovery.fence();
            var user = repository.lockUser(reference.user());

            if (user == null) {
                return null;
            }

            var session = repository.lockSession(user.id(), reference.family());

            if (session == null || session.revoked()) {
                return null;
            }

            if (!user.status().equals(StatusValues.ACTIVE)
                    || !user.verified()
                    || !session.refreshHash().equals(hash)
                    || !session.refreshExpiry().isAfter(clock.instant())) {
                repository.revoke(user.id(), session.family());

                return null; // Commit revocation before reporting HTTP 401.
            }

            String next = CryptoTokens.random(AuthPolicy.TOKEN_BYTES);
            UUID jti = UUID.randomUUID();
            repository.rotate(
                    session, jti, CryptoTokens.hash(next), clock.instant());

            return TokenResponseDto.builder()
                    .accessToken(
                            jwt.issue(
                                    user.id(),
                                    session.device(),
                                    jti,
                                    recovery.current().epoch()))
                    .refreshToken(next)
                    .tokenType(AuthPolicy.BEARER_SCHEME)
                    .expiresIn(AuthPolicy.ACCESS_TTL_SECONDS)
                    .build();
        });

        if (result == null) {
            throw AuthFailure.unauthorized();
        }

        return result;
    }

    @Override
    public AccessIdentityDto authenticate(String access) {
        var identity = jwt.verify(access);

        if (!identity.epoch().equals(recovery.ready().epoch())) {
            throw AuthFailure.unauthorized();
        }

        if (repository.liveSession(identity, clock.instant()) == null) {
            throw AuthFailure.unauthorized();
        }

        return identity;
    }

    private UserEntity lockAuthenticated(AccessIdentityDto identity) {
        var user = repository.lockUser(identity.userId());

        if (user == null
                || !user.status().equals(StatusValues.ACTIVE)
                || repository.liveSession(identity, clock.instant()) == null) {
            throw AuthFailure.unauthorized();
        }

        return user;
    }

    @Override
    public ProfileResponseDto me(AccessIdentityDto identity) {
        return transaction.execute(
                status -> {
                    recovery.fence();
                    var user = lockAuthenticated(identity);

                    return ProfileResponseDto.builder()
                            .id(user.id())
                            .email(user.email())
                            .baseCurrencyCode(user.currency())
                            .build();
                });
    }

    @Override
    public void logout(AccessIdentityDto identity) {
        transaction.executeWithoutResult(
                status -> {
                    recovery.fence();
                    lockAuthenticated(identity);
                    var reference = repository.liveSession(identity, clock.instant());
                    repository.revoke(identity.userId(), reference.family());
                });
    }

    @Override
    public void delete(AccessIdentityDto identity, String password) {
        transaction.executeWithoutResult(
                status -> {
                    recovery.fence();
                    var user = lockAuthenticated(identity);

                    if (!passwords.matches(password, user.hash())) {
                        throw AuthFailure.builder()
                                .status(HttpStatus.FORBIDDEN.value())
                                .code(ErrorCodes.DELETE_REJECTED)
                                .build();
                    }

                    context.setUser(user.id());
                    repository.deleteUser(user.id());
                });
    }
}
