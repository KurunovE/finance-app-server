package com.financetracker.repository.finance.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.financetracker.mapper.finance.FinanceSnapshotMapper;
import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.entity.finance.CategoryBudgetEntity;
import com.financetracker.model.entity.finance.CategoryEntity;
import com.financetracker.model.entity.finance.ExpenseEntity;
import com.financetracker.model.entity.finance.FinancialEntity;
import com.financetracker.model.entity.finance.MonthlyBudgetEntity;
import com.financetracker.model.enums.EntityType;
import com.financetracker.repository.finance.FinanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public final class JdbcFinanceRepository implements FinanceRepository {

    private static final String CATEGORIES = "categories";
    private static final String EXPENSES = "expenses";
    private static final String MONTHLY_BUDGETS = "monthly_budgets";
    private static final String CATEGORY_BUDGETS = "category_budgets";
    private static final String USER_ID = "user_id";
    private static final String CREATED_AT = "created_at";
    private static final String UPDATED_AT = "updated_at";
    private static final String NORMALIZED_NAME = "normalized_name";
    private static final String SORT_ORDER_COLUMN = "sort_order";
    private static final String CATEGORY_ID_COLUMN = "category_id";
    private static final String AMOUNT_MINOR_COLUMN = "amount_minor";
    private static final String OCCURRED_ON_COLUMN = "occurred_on";
    private static final String MONTH_START_COLUMN = "month_start";
    private static final String LIMIT_MINOR_COLUMN = "limit_minor";
    private static final String NORMALIZED_NAME_PREDICATE = "normalized_name = ?";
    private static final String MONTH_START_PREDICATE = "month_start = ?";
    private static final String MONTH_START_AND_CATEGORY_ID = "month_start = ? AND category_id = ?";
    private static final String UNKNOWN_ENTITY_TYPE = "Unknown entity type";

    private final JdbcOperations jdbc;
    private final FinanceSnapshotMapper snapshots;

    private String table(EntityType type) {
        return switch (type) {
            case CATEGORY -> CATEGORIES;
            case EXPENSE -> EXPENSES;
            case MONTHLY_BUDGET -> MONTHLY_BUDGETS;
            case CATEGORY_BUDGET -> CATEGORY_BUDGETS;
        };
    }

    @Override
    public Map<String, Object> find(EntityType type, UUID user, UUID id) {
        var rows = jdbc.query(
                """
        SELECT *
        FROM app.%s
        WHERE user_id = ?
            AND id = ?
        """
                        .formatted(table(type)),
                (row, index) -> snapshot(type, row),
                user,
                id);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> snapshot(EntityType type, ResultSet row) throws SQLException {
        UUID id = row.getObject(JsonFields.ID, UUID.class), user = row.getObject(USER_ID, UUID.class);
        long version = row.getLong(JsonFields.VERSION);
        var created = row.getTimestamp(CREATED_AT).toInstant();
        var updated = row.getTimestamp(UPDATED_AT).toInstant();
        FinancialEntity entity = switch (type) {
            case CATEGORY ->
                    CategoryEntity.builder()
                            .id(id)
                            .userId(user)
                            .version(version)
                            .createdAt(created)
                            .updatedAt(updated)
                            .name(row.getString(JsonFields.NAME))
                            .normalizedName(row.getString(NORMALIZED_NAME))
                            .archived(row.getBoolean(JsonFields.ARCHIVED))
                            .sortOrder(row.getInt(SORT_ORDER_COLUMN))
                            .build();
            case EXPENSE ->
                    ExpenseEntity.builder()
                            .id(id)
                            .userId(user)
                            .version(version)
                            .createdAt(created)
                            .updatedAt(updated)
                            .categoryId(row.getObject(CATEGORY_ID_COLUMN, UUID.class))
                            .amountMinor(row.getLong(AMOUNT_MINOR_COLUMN))
                            .occurredOn(row.getDate(OCCURRED_ON_COLUMN).toLocalDate())
                            .note(row.getString(JsonFields.NOTE))
                            .build();
            case MONTHLY_BUDGET ->
                    MonthlyBudgetEntity.builder()
                            .id(id)
                            .userId(user)
                            .version(version)
                            .createdAt(created)
                            .updatedAt(updated)
                            .monthStart(row.getDate(MONTH_START_COLUMN).toLocalDate())
                            .limitMinor(row.getLong(LIMIT_MINOR_COLUMN))
                            .build();
            case CATEGORY_BUDGET ->
                    CategoryBudgetEntity.builder()
                            .id(id)
                            .userId(user)
                            .version(version)
                            .createdAt(created)
                            .updatedAt(updated)
                            .categoryId(row.getObject(CATEGORY_ID_COLUMN, UUID.class))
                            .monthStart(row.getDate(MONTH_START_COLUMN).toLocalDate())
                            .limitMinor(row.getLong(LIMIT_MINOR_COLUMN))
                            .build();
        };

        return snapshots.snapshot(entity);
    }

    @Override
    public Map<String, Object> logicalConflict(
            EntityType type, UUID user, UUID id, Map<String, Object> data, String normalizedName) {
        String predicate;
        Object[] parameters;
        switch (type) {
            case EXPENSE -> {
                return null;
            }

            case CATEGORY -> {
                predicate = NORMALIZED_NAME_PREDICATE;
                parameters = new Object[] {user, id, normalizedName};
            }

            case MONTHLY_BUDGET -> {
                predicate = MONTH_START_PREDICATE;
                parameters =
                        new Object[] {
                            user, id, LocalDate.parse((String) data.get(JsonFields.MONTH_START))
                        };
            }

            case CATEGORY_BUDGET -> {
                predicate = MONTH_START_AND_CATEGORY_ID;
                parameters =
                        new Object[] {
                            user,
                            id,
                            LocalDate.parse((String) data.get(JsonFields.MONTH_START)),
                            UUID.fromString((String) data.get(JsonFields.CATEGORY_ID))
                        };
            }

            default -> throw new IllegalArgumentException(UNKNOWN_ENTITY_TYPE);
        }

        var rows = jdbc.query(
                """
        SELECT *
        FROM app.%s
        WHERE user_id = ?
            AND id <> ?
            AND %s
        """
                        .formatted(table(type), predicate),
                (row, index) -> snapshot(type, row),
                parameters);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void create(
            EntityType type, UUID user, UUID id, Map<String, Object> data, String normalizedName) {
        switch (type) {
            case CATEGORY ->
                    jdbc.update(
                            """
                            INSERT INTO app.categories(user_id, id, name, normalized_name, archived, sort_order)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                            user,
                            id,
                            data.get(JsonFields.NAME),
                            normalizedName,
                            data.get(JsonFields.ARCHIVED),
                            data.get(JsonFields.SORT_ORDER));
            case EXPENSE ->
                    jdbc.update(
                            """
                            INSERT INTO app.expenses(user_id, id, category_id, amount_minor, occurred_on, note)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                            user,
                            id,
                            UUID.fromString((String) data.get(JsonFields.CATEGORY_ID)),
                            data.get(JsonFields.AMOUNT_MINOR),
                            LocalDate.parse((String) data.get(JsonFields.OCCURRED_ON)),
                            data.get(JsonFields.NOTE));
            case MONTHLY_BUDGET ->
                    jdbc.update(
                            """
                            INSERT INTO app.monthly_budgets(user_id, id, month_start, limit_minor)
                            VALUES (?, ?, ?, ?)
                            """,
                            user,
                            id,
                            LocalDate.parse((String) data.get(JsonFields.MONTH_START)),
                            data.get(JsonFields.LIMIT_MINOR));
            case CATEGORY_BUDGET ->
                    jdbc.update(
                            """
                            INSERT INTO app.category_budgets(user_id, id, category_id, month_start, limit_minor)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                            user,
                            id,
                            UUID.fromString((String) data.get(JsonFields.CATEGORY_ID)),
                            LocalDate.parse((String) data.get(JsonFields.MONTH_START)),
                            data.get(JsonFields.LIMIT_MINOR));
        }
    }

    @Override
    public int update(
            EntityType type,
            UUID user,
            UUID id,
            long expected,
            Map<String, Object> data,
            String normalizedName) {
        return switch (type) {
            case CATEGORY ->
                    jdbc.update(
                            """
                            UPDATE app.categories
                            SET name = ?,
                                normalized_name = ?,
                                archived = ?,
                                sort_order = ?,
                                version = version + 1,
                                updated_at = now()
                            WHERE user_id = ?
                                AND id = ?
                                AND version = ?
                            """,
                            data.get(JsonFields.NAME),
                            normalizedName,
                            data.get(JsonFields.ARCHIVED),
                            data.get(JsonFields.SORT_ORDER),
                            user,
                            id,
                            expected);
            case EXPENSE ->
                    jdbc.update(
                            """
                            UPDATE app.expenses
                            SET category_id = ?,
                                amount_minor = ?,
                                occurred_on = ?,
                                note = ?,
                                version = version + 1,
                                updated_at = now()
                            WHERE user_id = ?
                                AND id = ?
                                AND version = ?
                            """,
                            UUID.fromString((String) data.get(JsonFields.CATEGORY_ID)),
                            data.get(JsonFields.AMOUNT_MINOR),
                            LocalDate.parse((String) data.get(JsonFields.OCCURRED_ON)),
                            data.get(JsonFields.NOTE),
                            user,
                            id,
                            expected);
            case MONTHLY_BUDGET ->
                    jdbc.update(
                            """
                            UPDATE app.monthly_budgets
                            SET month_start = ?,
                                limit_minor = ?,
                                version = version + 1,
                                updated_at = now()
                            WHERE user_id = ?
                                AND id = ?
                                AND version = ?
                            """,
                            LocalDate.parse((String) data.get(JsonFields.MONTH_START)),
                            data.get(JsonFields.LIMIT_MINOR),
                            user,
                            id,
                            expected);
            case CATEGORY_BUDGET ->
                    jdbc.update(
                            """
                            UPDATE app.category_budgets
                            SET category_id = ?,
                                month_start = ?,
                                limit_minor = ?,
                                version = version + 1,
                                updated_at = now()
                            WHERE user_id = ?
                                AND id = ?
                                AND version = ?
                            """,
                            UUID.fromString((String) data.get(JsonFields.CATEGORY_ID)),
                            LocalDate.parse((String) data.get(JsonFields.MONTH_START)),
                            data.get(JsonFields.LIMIT_MINOR),
                            user,
                            id,
                            expected);
        };
    }

    @Override
    public int delete(EntityType type, UUID user, UUID id, long expected) {
        return jdbc.update(
                """
                DELETE FROM app.%s
                WHERE user_id = ?
                    AND id = ?
                    AND version = ?
                """
                        .formatted(table(type)),
                user,
                id,
                expected);
    }
}
