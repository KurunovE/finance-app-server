package com.financetracker.repository.sync.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.model.dto.sync.SyncOperationDto;
import com.financetracker.model.enums.Action;
import com.financetracker.repository.sync.SyncRepository;
import com.financetracker.support.json.SyncJson;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public final class JdbcSyncRepository implements SyncRepository {

    private static final String ENTITY_TYPE_2 = "entity_type";
    private static final String ENTITY_ID_2 = "entity_id";
    private static final String ENTITY_VERSION_2 = "entity_version";
    private static final String PAYLOAD = "payload";

    private final JdbcOperations jdbc;
    private final SyncJson json;

    @Override
    public void lockState(UUID user) {
        jdbc.queryForObject(
                """
                SELECT last_seq
                FROM app.user_sync_state
                WHERE user_id = ?
                FOR UPDATE
                """,
                Long.class,
                user);
    }

    @Override
    public Long lastSequence(UUID user) {
        var states = jdbc.queryForList(
                """
            SELECT last_seq
            FROM app.user_sync_state
            WHERE user_id = ?
            """,
                Long.class,
                user);

        return states.isEmpty() ? null : states.getFirst();
    }

    @Override
    public Map<String, Object> operation(UUID user, UUID operation) {
        var rows = jdbc.queryForList(
                """
            SELECT request_hash, result_payload::text
            FROM app.sync_operations
            WHERE user_id = ?
                AND operation_id = ?
            """,
                user,
                operation);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public boolean deleted(UUID user, SyncOperationDto operation) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                    SELECT EXISTS (
                        SELECT 1
                        FROM app.sync_changes
                        WHERE user_id = ?
                            AND entity_type = ?
                            AND entity_id = ?
                            AND operation = 'DELETE'
                    )
                    """,
                        Boolean.class,
                        user,
                        operation.entityType().name(),
                        operation.entityId()));
    }

    @Override
    public void appendChange(
            UUID user, SyncOperationDto operation, long version, Map<String, Object> snapshot) {
        long seq = jdbc.queryForObject(
                """
            UPDATE app.user_sync_state
            SET last_seq = last_seq + 1
            WHERE user_id = ?
            RETURNING last_seq
            """,
                Long.class,
                user);
        jdbc.update(
                """
                INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                user,
                seq,
                operation.entityType().name(),
                operation.entityId(),
                operation.action() == Action.DELETE ? StatusValues.DELETE : StatusValues.UPSERT,
                version,
                snapshot == null ? null : json.write(snapshot));
    }

    @Override
    public void saveOperation(UUID user, UUID operation, String hash, Map<String, Object> result) {
        jdbc.update(
                """
                INSERT INTO app.sync_operations(user_id, operation_id, request_hash, result_status, result_payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """,
                user,
                operation,
                hash,
                result.get(JsonFields.STATUS),
                json.write(result));
    }

    @Override
    public List<Map<String, Object>> changes(UUID user, long cursor, long cutoff, int limit) {
        return jdbc.query(
                """
                SELECT *
                FROM app.sync_changes
                WHERE user_id = ?
                    AND seq > ?
                    AND seq <= ?
                ORDER BY seq
                LIMIT ?
                """,
                (row, index) -> {
                    var item = new LinkedHashMap<String, Object>();
                    item.put(JsonFields.SEQ, row.getLong(JsonFields.SEQ));
                    item.put(JsonFields.ENTITY_TYPE, row.getString(ENTITY_TYPE_2));
                    item.put(
                            JsonFields.ENTITY_ID,
                            row.getObject(ENTITY_ID_2, UUID.class).toString());
                    item.put(JsonFields.OPERATION, row.getString(JsonFields.OPERATION));
                    item.put(JsonFields.ENTITY_VERSION, row.getLong(ENTITY_VERSION_2));
                    String payload = row.getString(PAYLOAD);
                    item.put(JsonFields.SNAPSHOT, payload == null ? null : json.read(payload));

                    return item;
                },
                user,
                cursor,
                cutoff,
                limit);
    }
}
