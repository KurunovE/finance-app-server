package com.financetracker.repository.auth.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.financetracker.policy.AuthPolicy;
import com.financetracker.repository.auth.AccountTokenRepository;
import com.financetracker.repository.support.QueryResults;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public final class JdbcAccountTokenRepository implements AccountTokenRepository {

    private final JdbcOperations jdbc;

    @Override
    public UUID user(String hash, String purpose, Instant now) {
        return QueryResults.first(
                jdbc.query(
                        """
                        SELECT user_id
                        FROM app.account_tokens
                        WHERE token_hash = ?
                            AND purpose = ?
                            AND used_at IS NULL
                            AND expires_at > ?
                        """,
                        (row, index) -> row.getObject(1, UUID.class),
                        hash,
                        purpose,
                        Timestamp.from(now)));
    }

    @Override
    public boolean coolingDown(UUID user, String purpose, Instant now) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.account_tokens
                            WHERE user_id = ?
                                AND purpose = ?
                                AND created_at > ?
                        )
                        """,
                        Boolean.class,
                        user,
                        purpose,
                        Timestamp.from(now.minusSeconds(AuthPolicy.RESEND_COOLDOWN_SECONDS))));
    }

    @Override
    public void replace(UUID user, String purpose, String hash, Instant now, Instant expires) {
        jdbc.update(
                """
                DELETE FROM app.account_tokens
                WHERE user_id = ?
                    AND purpose = ?
                """,
                user,
                purpose);
        jdbc.update(
                """
                INSERT INTO app.account_tokens(token_hash, user_id, purpose, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                hash,
                user,
                purpose,
                Timestamp.from(now),
                Timestamp.from(expires));
    }

    @Override
    public boolean claim(UUID user, String hash, String purpose, Instant now) {
        var rows = jdbc.queryForList(
                """
        SELECT token_hash
        FROM app.account_tokens
        WHERE user_id = ?
            AND token_hash = ?
            AND purpose = ?
            AND used_at IS NULL
            AND expires_at > ?
        FOR UPDATE
        """,
                String.class,
                user,
                hash,
                purpose,
                Timestamp.from(now));

        return !rows.isEmpty();
    }

    @Override
    public void verify(UUID user, String hash, Instant now) {
        jdbc.update(
                """
                UPDATE app.users
                SET email_verified_at = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                Timestamp.from(now),
                user);
        jdbc.update(
                """
                UPDATE app.account_tokens
                SET used_at = ?
                WHERE user_id = ?
                    AND token_hash = ?
                """,
                Timestamp.from(now),
                user,
                hash);
    }

    @Override
    public void reset(UUID user, String encoded, Instant now) {
        jdbc.update(
                """
                UPDATE app.users
                SET password_hash = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                encoded,
                user);
        jdbc.update(
                """
                UPDATE app.auth_sessions
                SET revoked_at = coalesce(revoked_at, ?),
                    updated_at = now()
                WHERE user_id = ?
                """,
                Timestamp.from(now),
                user);
        jdbc.update(
                """
                UPDATE app.account_tokens
                SET used_at = coalesce(used_at, ?)
                WHERE user_id = ?
                """,
                Timestamp.from(now),
                user);
    }
}
