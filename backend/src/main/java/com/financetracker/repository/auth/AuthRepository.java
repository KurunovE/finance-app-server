package com.financetracker.repository.auth;

import java.time.Instant;
import java.util.UUID;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.SessionReferenceDto;
import com.financetracker.model.entity.auth.AuthSessionEntity;
import com.financetracker.model.entity.auth.UserEntity;

public interface AuthRepository {
    UserEntity byEmail(String email);

    UserEntity lockUser(UUID id);

    boolean availableInvite(String hash, Instant now);

    boolean lockInvite(String hash, Instant now);

    void insertUser(UUID id, String email, String normalized, String hash, String currency);

    void consumeInvite(String hash, UUID user, Instant now);

    void initialCategories(UUID user);

    void upgradePassword(UUID user, String hash);

    void insertDevice(UUID user, UUID device);

    void insertSession(
            UUID user, UUID device, UUID family, UUID jti, String refreshHash, Instant now);

    SessionReferenceDto reference(String hash);

    AuthSessionEntity lockSession(UUID user, UUID family);

    void rotate(AuthSessionEntity session, UUID jti, String hash, Instant now);

    void revoke(UUID user, UUID family);

    SessionReferenceDto liveSession(AccessIdentityDto identity, Instant now);

    void deleteUser(UUID user);
}
