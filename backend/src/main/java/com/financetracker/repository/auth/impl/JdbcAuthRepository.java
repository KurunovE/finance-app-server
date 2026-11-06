package com.financetracker.repository.auth.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.SessionReferenceDto;
import com.financetracker.model.entity.auth.AuthSessionEntity;
import com.financetracker.model.entity.auth.UserEntity;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.policy.FinancePolicy;
import com.financetracker.repository.auth.AuthRepository;
import com.financetracker.repository.support.QueryResults;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public final class JdbcAuthRepository implements AuthRepository {

    private static final String EMAIL = "email";
    private static final String EMAIL_NORM = "email_norm";
    private static final String PASSWORD_HASH = "password_hash";
    private static final String BASE_CURRENCY_CODE = "base_currency_code";
    private static final String EMAIL_VERIFIED_AT = "email_verified_at";
    private static final String FAMILY_ID = "family_id";
    private static final String DEVICE_ID = "device_id";
    private static final String ACCESS_JTI = "access_jti";
    private static final String REFRESH_TOKEN_HASH = "refresh_token_hash";
    private static final String REFRESH_EXPIRES_AT = "refresh_expires_at";
    private static final String REVOKED_AT = "revoked_at";

    private final JdbcOperations jdbc;

    @Override
    public UserEntity byEmail(String email) {
        return QueryResults.first(
                jdbc.query(
                        """
                        SELECT *
                        FROM app.users
                        WHERE email_norm = ?
                        """,
                        this::user,
                        email));
    }

    @Override
    public UserEntity lockUser(UUID id) {
        return QueryResults.first(
                jdbc.query(
                        """
                        SELECT *
                        FROM app.users
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        this::user,
                        id));
    }

    private UserEntity user(ResultSet row, int ignoredIndex) throws SQLException {
        return UserEntity.builder()
                .id(row.getObject(JsonFields.ID, UUID.class))
                .email(row.getString(EMAIL))
                .normalizedEmail(row.getString(EMAIL_NORM))
                .hash(row.getString(PASSWORD_HASH))
                .currency(row.getString(BASE_CURRENCY_CODE))
                .status(row.getString(JsonFields.STATUS))
                .verified(row.getTimestamp(EMAIL_VERIFIED_AT) != null)
                .build();
    }

    @Override
    public boolean availableInvite(String hash, Instant now) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.registration_invites
                            WHERE code_hash = ?
                                AND used_at IS NULL
                                AND expires_at > ?
                        )
                        """,
                        Boolean.class,
                        hash,
                        Timestamp.from(now)));
    }

    @Override
    public boolean lockInvite(String hash, Instant now) {
        return !jdbc.queryForList(
                        """
                        SELECT code_hash
                        FROM app.registration_invites
                        WHERE code_hash = ?
                            AND used_at IS NULL
                            AND expires_at > ?
                        FOR UPDATE
                        """,
                        String.class,
                        hash,
                        Timestamp.from(now))
                .isEmpty();
    }

    @Override
    public void insertUser(UUID id, String email, String normalized, String hash, String currency) {
        jdbc.update(
                """
                INSERT INTO app.users(id, email, email_norm, password_hash, base_currency_code)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                email,
                normalized,
                hash,
                currency);
    }

    @Override
    public void consumeInvite(String hash, UUID user, Instant now) {
        jdbc.update(
                """
                UPDATE app.registration_invites
                SET used_at = ?,
                    used_by = ?
                WHERE code_hash = ?
                """,
                Timestamp.from(now),
                user,
                hash);
    }

    @Override
    public void initialCategories(UUID user) {
        var names = FinancePolicy.INITIAL_CATEGORIES;
        jdbc.update(
                """
                INSERT INTO app.user_sync_state(user_id, last_seq)
                VALUES (?, ?)
                """,
                user,
                names.size());

        for (int index = 0; index < names.size(); index++) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO app.categories(user_id, id, name, normalized_name, sort_order)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    user,
                    id,
                    names.get(index),
                    names.get(index).toLowerCase(Locale.ROOT),
                    index);
            jdbc.update(
                    """
                    INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                    SELECT user_id, ?, 'CATEGORY', id, 'UPSERT', version, jsonb_build_object('id', id, 'name', name, 'archived',
                        archived, 'sortOrder', sort_order, 'version', version, 'createdAt', created_at, 'updatedAt', updated_at)
                    FROM app.categories
                    WHERE user_id = ?
                        AND id = ?
                    """,
                    index + 1,
                    user,
                    id);
        }
    }

    @Override
    public void upgradePassword(UUID user, String hash) {
        jdbc.update(
                """
                UPDATE app.users
                SET password_hash = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                hash,
                user);
    }

    @Override
    public void insertDevice(UUID user, UUID device) {
        jdbc.update(
                """
                INSERT INTO app.devices(user_id, id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
                user,
                device);
    }

    @Override
    public void insertSession(
            UUID user, UUID device, UUID family, UUID jti, String refreshHash, Instant now) {
        insertDevice(user, device);
        jdbc.update(
                """
                UPDATE app.auth_sessions
                SET revoked_at = coalesce(revoked_at, now()),
                    updated_at = now()
                WHERE user_id = ?
                    AND device_id = ?
                    AND revoked_at IS NULL
                """,
                user,
                device);
        jdbc.update(
                """
                INSERT INTO app.auth_sessions(family_id, user_id, device_id, access_jti, refresh_token_hash, access_expires_at,
                    refresh_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                family,
                user,
                device,
                jti,
                refreshHash,
                Timestamp.from(now.plusSeconds(AuthPolicy.ACCESS_TTL_SECONDS)),
                Timestamp.from(now.plusSeconds(AuthPolicy.REFRESH_TTL.toSeconds())));
    }

    @Override
    public SessionReferenceDto reference(String hash) {
        return QueryResults.first(
                jdbc.query(
                        """
                        SELECT user_id, family_id
                        FROM app.auth_sessions
                        WHERE refresh_token_hash = ?
                        UNION
                        SELECT s.user_id, s.family_id
                        FROM app.used_refresh_tokens t
                        JOIN app.auth_sessions s ON s.family_id = t.family_id
                        WHERE t.token_hash = ?
                        """,
                        (row, index) ->
                                SessionReferenceDto.builder()
                                        .user(row.getObject(1, UUID.class))
                                        .family(row.getObject(2, UUID.class))
                                        .build(),
                        hash,
                        hash));
    }

    @Override
    public AuthSessionEntity lockSession(UUID user, UUID family) {
        return QueryResults.first(
                jdbc.query(
                        """
                        SELECT *
                        FROM app.auth_sessions
                        WHERE user_id = ?
                            AND family_id = ?
                        FOR UPDATE
                        """,
                        (row, index) ->
                                AuthSessionEntity.builder()
                                        .family(row.getObject(FAMILY_ID, UUID.class))
                                        .user(user)
                                        .device(row.getObject(DEVICE_ID, UUID.class))
                                        .jti(row.getObject(ACCESS_JTI, UUID.class))
                                        .refreshHash(row.getString(REFRESH_TOKEN_HASH))
                                        .refreshExpiry(
                                                row.getTimestamp(REFRESH_EXPIRES_AT).toInstant())
                                        .revoked(row.getTimestamp(REVOKED_AT) != null)
                                        .build(),
                        user,
                        family));
    }

    @Override
    public void rotate(AuthSessionEntity session, UUID jti, String hash, Instant now) {
        jdbc.update(
                """
                INSERT INTO app.used_refresh_tokens(token_hash, family_id, expires_at)
                VALUES (?, ?, ?)
                """,
                session.refreshHash(),
                session.family(),
                Timestamp.from(session.refreshExpiry()));
        jdbc.update(
                """
                UPDATE app.auth_sessions
                SET access_jti = ?,
                    refresh_token_hash = ?,
                    access_expires_at = ?,
                    refresh_expires_at = ?,
                    updated_at = now()
                WHERE user_id = ?
                    AND family_id = ?
                """,
                jti,
                hash,
                Timestamp.from(now.plusSeconds(AuthPolicy.ACCESS_TTL_SECONDS)),
                Timestamp.from(now.plusSeconds(AuthPolicy.REFRESH_TTL.toSeconds())),
                session.user(),
                session.family());
    }

    @Override
    public void revoke(UUID user, UUID family) {
        jdbc.update(
                """
                UPDATE app.auth_sessions
                SET revoked_at = coalesce(revoked_at, now()),
                    updated_at = now()
                WHERE user_id = ?
                    AND family_id = ?
                """,
                user,
                family);
    }

    @Override
    public SessionReferenceDto liveSession(AccessIdentityDto identity, Instant now) {
        return QueryResults.first(
                jdbc.query(
                        """
                        SELECT s.user_id, s.family_id
                        FROM app.auth_sessions s
                        JOIN app.users u ON u.id = s.user_id
                        WHERE s.user_id = ?
                            AND s.device_id = ?
                            AND s.access_jti = ?
                            AND s.revoked_at IS NULL
                            AND s.access_expires_at > ?
                            AND u.status = 'ACTIVE'
                            AND u.email_verified_at IS NOT NULL
                        """,
                        (row, index) ->
                                SessionReferenceDto.builder()
                                        .user(row.getObject(1, UUID.class))
                                        .family(row.getObject(2, UUID.class))
                                        .build(),
                        identity.userId(),
                        identity.deviceId(),
                        identity.jti(),
                        Timestamp.from(now)));
    }

    @Override
    public void deleteUser(UUID user) {
        jdbc.update(
                """
                DELETE FROM app.users
                WHERE id = ?
                """,
                user);
    }
}
