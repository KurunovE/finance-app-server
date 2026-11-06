package com.financetracker.repository.sync.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.financetracker.model.entity.sync.RecoveryStateEntity;
import com.financetracker.model.entity.sync.SyncRecoveryEntity;
import com.financetracker.policy.SyncPolicy;
import com.financetracker.repository.sync.RecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public final class JdbcRecoveryRepository implements RecoveryRepository {

    private final JdbcOperations jdbc;

    @Override
    public RecoveryStateEntity current() {
        var rows = jdbc.query(
                """
        SELECT epoch, phase
        FROM app.recovery_state
        WHERE singleton
        """,
                (row, index) ->
                        RecoveryStateEntity.builder()
                                .epoch(row.getObject(1, UUID.class))
                                .phase(row.getString(2))
                                .build());

        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void lockShared() {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock_shared(?)
                """,
                String.class,
                SyncPolicy.RECOVERY_LOCK_KEY);
    }

    @Override
    public void lockExclusive() {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(?)
                """,
                String.class,
                SyncPolicy.RECOVERY_LOCK_KEY);
    }

    @Override
    public void prepare() {
        jdbc.queryForObject(
                """
                SELECT app.begin_recovery()
                """,
                UUID.class);
    }

    @Override
    public boolean deviceReady(UUID user, UUID device, UUID epoch) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.devices
                            WHERE user_id = ?
                                AND id = ?
                                AND synced_epoch = ?
                        )
                        """,
                        Boolean.class,
                        user,
                        device,
                        epoch));
    }

    @Override
    public SyncRecoveryEntity pending(UUID user, UUID device, UUID epoch) {
        var rows = jdbc.query(
                """
        SELECT id, epoch, cutoff_seq, delivered_seq
        FROM app.sync_recoveries
        WHERE user_id = ?
            AND device_id = ?
            AND epoch = ?
            AND acknowledged_at IS NULL
        FOR UPDATE
        """,
                this::session,
                user,
                device,
                epoch);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public SyncRecoveryEntity lockSession(UUID user, UUID device, UUID id) {
        var rows = jdbc.query(
                """
        SELECT id, epoch, cutoff_seq, delivered_seq
        FROM app.sync_recoveries
        WHERE user_id = ?
            AND device_id = ?
            AND id = ?
        FOR UPDATE
        """,
                this::session,
                user,
                device,
                id);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private SyncRecoveryEntity session(ResultSet row, int ignoredIndex) throws SQLException {
        return SyncRecoveryEntity.builder()
                .recoveryId(row.getObject(1, UUID.class))
                .epoch(row.getObject(2, UUID.class))
                .cutoffSeq(row.getLong(3))
                .deliveredSeq(row.getLong(4))
                .build();
    }

    @Override
    public void clearAcknowledged(UUID user, UUID device) {
        jdbc.update(
                """
                DELETE FROM app.sync_recoveries
                WHERE user_id = ?
                    AND device_id = ?
                    AND acknowledged_at IS NOT NULL
                """,
                user,
                device);
    }

    @Override
    public void insert(UUID user, UUID device, SyncRecoveryEntity session) {
        jdbc.update(
                """
                INSERT INTO app.sync_recoveries(id, user_id, device_id, epoch, cutoff_seq)
                VALUES (?, ?, ?, ?, ?)
                """,
                session.recoveryId(),
                user,
                device,
                session.epoch(),
                session.cutoffSeq());
    }

    @Override
    public void delivered(UUID user, UUID device, UUID id, long next) {
        jdbc.update(
                """
                UPDATE app.sync_recoveries
                SET delivered_seq = greatest(delivered_seq, ?)
                WHERE user_id = ?
                    AND device_id = ?
                    AND id = ?
                """,
                next,
                user,
                device,
                id);
    }

    @Override
    public void acknowledge(UUID user, UUID device, UUID id, UUID epoch) {
        jdbc.update(
                """
                UPDATE app.sync_recoveries
                SET acknowledged_at = coalesce(acknowledged_at, now())
                WHERE user_id = ?
                    AND device_id = ?
                    AND id = ?
                """,
                user,
                device,
                id);
        jdbc.update(
                """
                UPDATE app.devices
                SET synced_epoch = ?
                WHERE user_id = ?
                    AND id = ?
                """,
                epoch,
                user,
                device);
    }
}
