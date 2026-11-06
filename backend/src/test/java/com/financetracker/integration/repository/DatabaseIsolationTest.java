package com.financetracker.integration.repository;

import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;

import com.financetracker.repository.context.UserTransactionContext;
import com.financetracker.repository.context.impl.DatabaseUserTransactionContext;
import com.financetracker.support.PostgresFixture;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseIsolationTest {
    private static final PostgresFixture DATABASE = new PostgresFixture();
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static UserTransactionContext context;
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();

    @BeforeAll
    static void start() {
        DATABASE.start();
        DATABASE.flyway().migrate();
        pool = DATABASE.runtimePool();
        jdbc = new JdbcTemplate(pool);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
        context = new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource());
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }

        DATABASE.close();
    }

    @BeforeEach
    void seedAccounts() {
        seed(userA);
        seed(userB);
    }

    private void seed(UUID user) {
        transaction.executeWithoutResult(
                status -> {
                    insertUser(user);
                    context.setUser(user);
                    jdbc.update(
                            """
                            INSERT INTO app.categories(user_id, id, name, normalized_name)
                            VALUES (?, ?, 'Food', 'food')
                            """,
                            user,
                            category);
                    jdbc.update(
                            """
                            INSERT INTO app.expenses(user_id, id, category_id, amount_minor, occurred_on)
                            VALUES (?, ?, ?, 100, '2026-10-31')
                            """,
                            user,
                            UUID.randomUUID(),
                            category);
                    jdbc.update(
                            """
                            INSERT INTO app.monthly_budgets(user_id, id, month_start, limit_minor)
                            VALUES (?, ?, '2026-10-01', 1000)
                            """,
                            user,
                            UUID.randomUUID());
                    jdbc.update(
                            """
                            INSERT INTO app.category_budgets(user_id, id, category_id, month_start, limit_minor)
                            VALUES (?, ?, ?, '2026-10-01', 1000)
                            """,
                            user,
                            UUID.randomUUID(),
                            category);
                    jdbc.update(
                            """
                            INSERT INTO app.user_sync_state(user_id, last_seq)
                            VALUES (?, 1)
                            """,
                            user);
                    jdbc.update(
                            """
                            INSERT INTO app.sync_operations(user_id, operation_id, request_hash, result_status, result_payload)
                            VALUES (?, ?, ?, 'APPLIED', '{}')
                            """,
                            user,
                            UUID.randomUUID(),
                            "a".repeat(64));
                    jdbc.update(
                            """
                            INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                            VALUES (?, 1, 'CATEGORY', ?, 'UPSERT', 1, '{}')
                            """,
                            user,
                            category);
                });
    }

    private void insertUser(UUID user) {
        jdbc.update(
                """
                INSERT INTO app.users(id, email, email_norm, password_hash, base_currency_code)
                VALUES (?, ?, ?, 'test-hash', 'RUB')
                """,
                user,
                user + "@example.test",
                user + "@example.test");
    }

    private <T> T asUser(UUID user, Supplier<T> work) {
        return transaction.execute(
                status -> {
                    context.setUser(user);

                    return work.get();
                });
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("RLS закрывает чтение без контекста")
    @ValueSource(
            strings = {
                "categories",
                "expenses",
                "monthly_budgets",
                "category_budgets",
                "user_sync_state",
                "sync_operations",
                "sync_changes"
            })
    void allFinancialAndSyncTablesDenyWithoutContextAndFilterUnscopedReads(String table) {
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.%s
                        """
                                .formatted(table),
                        Integer.class));
        assertEquals(
                1,
                asUser(
                        userA,
                        () ->
                                jdbc.queryForObject(
                                        """
                                        SELECT count(*)
                                        FROM app.%s
                                        """
                                                .formatted(table),
                                        Integer.class)));
        assertEquals(
                userB,
                asUser(
                        userB,
                        () ->
                                jdbc.queryForObject(
                                        """
                                        SELECT user_id
                                        FROM app.%s
                                        """
                                                .formatted(table),
                                        UUID.class)));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("RLS закрывает изменение чужих строк")
    @ValueSource(
            strings = {
                "categories",
                "expenses",
                "monthly_budgets",
                "category_budgets",
                "user_sync_state"
            })
    void updateAndDeleteCannotReachForeignRowsEvenWithoutOwnerFilter(String table) {
        assertEquals(
                0,
                asUser(
                        userA,
                        () ->
                                jdbc.update(
                                        """
                                        UPDATE app.%s
                                        SET user_id = user_id
                                        WHERE user_id = ?
                                        """
                                                .formatted(table),
                                        userB)));
        assertEquals(
                1,
                asUser(
                        userA,
                        () ->
                                jdbc.update(
                                        """
                                        UPDATE app.%s
                                        SET user_id = user_id
                                        """
                                                .formatted(table))));
        assertEquals(
                0,
                asUser(
                        userA,
                        () ->
                                jdbc.update(
                                        """
                                        DELETE FROM app.%s
                                        WHERE user_id = ?
                                        """
                                                .formatted(table),
                                        userB)));
    }

    @Test
    @DisplayName("RLS запрещает вставку без своего владельца")
    void foreignAndContextFreeInsertsAreDenied() {
        assertSqlState(
                "42501",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.categories(user_id, id, name, normalized_name)
                                                VALUES (?, ?, 'Other', 'other')
                                                """,
                                                userB,
                                                UUID.randomUUID())));
        assertSqlState(
                "42501",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO app.categories(user_id, id, name, normalized_name)
                                VALUES (?, ?, 'Other', 'other')
                                """,
                                userA,
                                UUID.randomUUID()));
        assertSqlState(
                "42501",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                UPDATE app.categories
                                                SET user_id = ?
                                                """,
                                                userB)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                """
                INSERT INTO app.expenses(user_id, id, category_id, amount_minor, occurred_on)
                VALUES (?, gen_random_uuid(), ?, 1, '2026-11-01')
                """,
                """
                INSERT INTO app.category_budgets(user_id, id, category_id, month_start, limit_minor)
                VALUES (?, gen_random_uuid(), ?, '2026-11-01', 1)
                """,
                """
                INSERT INTO app.monthly_budgets(user_id, id, month_start, limit_minor)
                VALUES (?, ?, '2026-11-01', 1)
                """,
                """
                INSERT INTO app.user_sync_state(user_id, last_seq)
                SELECT ?, 0
                WHERE ?::uuid IS NOT NULL
                """,
                """
                INSERT INTO app.sync_operations(user_id, operation_id, request_hash, result_status, result_payload)
                VALUES (?, ?, repeat('a', 64), 'APPLIED', '{}')
                """,
                """
                INSERT INTO app.sync_changes(user_id, entity_id, seq, entity_type, operation, entity_version, payload)
                VALUES (?, ?, 2, 'EXPENSE', 'UPSERT', 1, '{}')
                """
            })
    @DisplayName("Все политики запрещают чужие вставки")
    void allRemainingPoliciesRejectForeignInserts(String sql) {
        assertSqlState("42501", () -> asUser(userA, () -> jdbc.update(sql, userB, category)));
    }

    @Test
    @DisplayName("Удаление затрагивает только свои строки")
    void unscopedDeleteRemovesOnlyOwnRows() {
        // A separate category without dependants allows an unscoped DELETE to succeed.
        UUID isolatedUser = UUID.randomUUID();
        transaction.executeWithoutResult(
                status -> {
                    insertUser(isolatedUser);
                    context.setUser(isolatedUser);
                    jdbc.update(
                            """
                            INSERT INTO app.categories(user_id, id, name, normalized_name)
                            VALUES (?, ?, 'Own', 'own')
                            """,
                            isolatedUser,
                            UUID.randomUUID());
                });
        assertEquals(
                1,
                asUser(
                        isolatedUser,
                        () ->
                                jdbc.update(
                                        """
                                        DELETE FROM app.categories
                                        """)));
        assertEquals(
                1,
                asUser(
                        userB,
                        () ->
                                jdbc.queryForObject(
                                        """
                                        SELECT count(*)
                                        FROM app.categories
                                        """,
                                        Integer.class)));
    }

    @Test
    @DisplayName("Журнал проверяет seq и tombstone")
    void journalsEnforceSequenceVersionStatusAndTombstoneConstraints() {
        for (String sql :
                new String[] {
                    """
                    INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                    VALUES (?, 0, 'EXPENSE', ?, 'UPSERT', 1, '{}')
                    """,
                    """
                    INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                    VALUES (?, 2, 'EXPENSE', ?, 'UPSERT', 0, '{}')
                    """,
                    """
                    INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                    VALUES (?, 2, 'EXPENSE', ?, 'DELETE', 1, '{}')
                    """,
                    """
                    INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                    VALUES (?, 2, 'CATEGORY', ?, 'DELETE', 1, NULL)
                    """,
                    """
                    INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                    VALUES (?, 2, 'EXPENSE', ?, 'UPSERT', 1, NULL)
                    """,
                    """
                    INSERT INTO app.sync_operations(user_id, operation_id, request_hash, result_status, result_payload)
                    VALUES (?, ?, repeat('a', 64), 'UNKNOWN', '{}')
                    """,
                    """
                    INSERT INTO app.sync_operations(user_id, operation_id, request_hash, result_status, result_payload)
                    VALUES (?, ?, repeat('a', 64), 'APPLIED', '[]')
                    """
                }) {
            assertSqlState(
                    "23514", () -> asUser(userA, () -> jdbc.update(sql, userA, UUID.randomUUID())));
        }

        assertEquals(
                1,
                asUser(
                        userA,
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                                        VALUES (?, 2, 'EXPENSE', ?, 'DELETE', 2, NULL)
                                        """,
                                        userA,
                                        UUID.randomUUID())));
        assertSqlState(
                "23505",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                                                VALUES (?, 2, 'EXPENSE', ?, 'DELETE', 2, NULL)
                                                """,
                                                userA,
                                                UUID.randomUUID())));
    }

    @Test
    @DisplayName("FK не допускает чужую категорию")
    void compositeForeignKeysRejectCategoryOwnedOnlyByOtherAccount() {
        UUID foreignCategory = UUID.randomUUID();
        asUser(
                userB,
                () ->
                        jdbc.update(
                                """
                                INSERT INTO app.categories(user_id, id, name, normalized_name)
                                VALUES (?, ?, 'Other', 'other')
                                """,
                                userB,
                                foreignCategory));
        assertSqlState(
                "23503",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.expenses(user_id, id, category_id, amount_minor, occurred_on)
                                                VALUES (?, ?, ?, 1, '2026-10-31')
                                                """,
                                                userA,
                                                UUID.randomUUID(),
                                                foreignCategory)));
        assertSqlState(
                "23503",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.category_budgets(user_id, id, category_id, month_start, limit_minor)
                                                VALUES (?, ?, ?, '2026-11-01', 100)
                                                """,
                                                userA,
                                                UUID.randomUUID(),
                                                foreignCategory)));
    }

    @Test
    @DisplayName("RLS-контекст очищается после транзакции")
    void contextIsClearedAfterCommitRollbackAndReadOnlyTransactionsOnSameConnection() {
        int pid = jdbc.queryForObject(
                """
        SELECT pg_backend_pid()
        """,
                Integer.class);

        for (UUID user : new UUID[] {userA, userB, userA}) {
            assertEquals(
                    user,
                    asUser(
                            user,
                            () ->
                                    jdbc.queryForObject(
                                            """
                                            SELECT user_id
                                            FROM app.categories
                                            """,
                                            UUID.class)));
            assertNull(
                    jdbc.queryForObject(
                            """
                            SELECT NULLIF(current_setting('app.current_user_id', true), '')
                            """,
                            String.class));
        }

        transaction.executeWithoutResult(
                status -> {
                    context.setUser(userB);
                    status.setRollbackOnly();
                });
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.categories
                        """,
                        Integer.class));
        TransactionTemplate readOnly = new TransactionTemplate(new DataSourceTransactionManager(pool));
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(
                status -> {
                    context.setUser(userA);
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    """
                                    SELECT count(*)
                                    FROM app.categories
                                    """,
                                    Integer.class));
                });
        assertEquals(
                pid,
                jdbc.queryForObject(
                        """
                        SELECT pg_backend_pid()
                        """,
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.categories
                        """,
                        Integer.class));
    }

    @Test
    @DisplayName("Неверная установка RLS-контекста запрещена")
    void helperRejectsMissingTransactionNullUserAndRepeatedOrChangedContext() {
        assertThrows(IllegalStateException.class, () -> context.setUser(userA));
        assertThrows(NullPointerException.class, () -> context.setUser(null));
        transaction.executeWithoutResult(
                status -> {
                    context.setUser(userA);
                    assertThrows(IllegalStateException.class, () -> context.setUser(userA));
                    assertThrows(IllegalStateException.class, () -> context.setUser(userB));
                });

        try (var otherPool = DATABASE.runtimePool()) {
            var other = new TransactionTemplate(new DataSourceTransactionManager(otherPool));
            other.executeWithoutResult(
                    status ->
                            assertThrows(
                                    IllegalStateException.class, () -> context.setUser(userA)));
        }
    }

    @Test
    @DisplayName("Propagation сохраняет границы RLS-контекста")
    void requiredPropagationSharesContextAndRequiresNewHasItsOwnConnection() {
        try (var concurrentPool = DATABASE.runtimePool()) {
            concurrentPool.setMaximumPoolSize(2);
            var concurrentJdbc = new JdbcTemplate(concurrentPool);
            var concurrentContext = new DatabaseUserTransactionContext(concurrentJdbc, concurrentJdbc.getDataSource());
            var manager = new DataSourceTransactionManager(concurrentPool);
            var outer = new TransactionTemplate(manager);
            var inner = new TransactionTemplate(manager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            outer.executeWithoutResult(
                    status -> {
                        concurrentContext.setUser(userA);
                        new TransactionTemplate(manager)
                                .executeWithoutResult(
                                        joined ->
                                                assertThrows(
                                                        IllegalStateException.class,
                                                        () -> concurrentContext.setUser(userB)));
                        inner.executeWithoutResult(
                                independent -> {
                                    assertEquals(
                                            0,
                                            concurrentJdbc.queryForObject(
                                                    """
                                                    SELECT count(*)
                                                    FROM app.categories
                                                    """,
                                                    Integer.class));
                                    concurrentContext.setUser(userB);
                                    assertEquals(
                                            userB,
                                            concurrentJdbc.queryForObject(
                                                    """
                                                    SELECT user_id
                                                    FROM app.categories
                                                    """,
                                                    UUID.class));
                                });
                        assertEquals(
                                userA,
                                concurrentJdbc.queryForObject(
                                        """
                                        SELECT user_id
                                        FROM app.categories
                                        """,
                                        UUID.class));
                    });
        }
    }

    @Test
    @DisplayName("Ошибка БД откатывает регистрацию и финансы")
    void registrationAndFinancialChangesRollBackTogetherOnConstraintFailure() {
        UUID newUser = UUID.randomUUID();
        assertSqlState(
                "23514",
                () ->
                        transaction.execute(
                                status -> {
                                    insertUser(newUser);
                                    context.setUser(newUser);
                                    jdbc.update(
                                            """
                                            INSERT INTO app.user_sync_state(user_id, last_seq)
                                            VALUES (?, 1)
                                            """,
                                            newUser);
                                    jdbc.update(
                                            """
                                            INSERT INTO app.categories(user_id, id, name, normalized_name)
                                            VALUES (?, ?, 'Food', 'food')
                                            """,
                                            newUser,
                                            category);
                                    jdbc.update(
                                            """
                                            INSERT INTO app.sync_changes(user_id, seq, entity_type, entity_id, operation, entity_version, payload)
                                            VALUES (?, 1, 'CATEGORY', ?, 'UPSERT', 1, '{}')
                                            """,
                                            newUser,
                                            category);

                                    return jdbc.update(
                                            """
                                            INSERT INTO app.expenses(user_id, id, category_id, amount_minor, occurred_on)
                                            VALUES (?, ?, ?, 0, '2026-10-31')
                                            """,
                                            newUser,
                                            UUID.randomUUID(),
                                            category);
                                }));
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.users
                        WHERE id = ?
                        """,
                        Integer.class,
                        newUser));
        asUser(
                newUser,
                () -> {
                    for (String table :
                            new String[] {
                                "categories", "expenses", "user_sync_state", "sync_changes"
                            }) {
                        assertEquals(
                                0,
                                jdbc.queryForObject(
                                        """
                                        SELECT count(*)
                                        FROM app.%s
                                        """
                                                .formatted(table),
                                        Integer.class));
                    }

                    return null;
                });
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Ограничения БД отвергают неверные финансы")
    @ValueSource(
            strings = {
                """
                UPDATE app.expenses
                SET amount_minor = 0
                """,
                        """
                        UPDATE app.expenses
                        SET amount_minor = - 1
                        """,
                """
                UPDATE app.expenses
                SET version = 0
                """,
                        """
                        UPDATE app.expenses
                        SET note = repeat('x', 501)
                        """,
                """
                UPDATE app.categories
                SET name = ' '
                """,
                        """
                        UPDATE app.categories
                        SET version = - 1
                        """,
                """
                UPDATE app.categories
                SET normalized_name = ''
                """,
                        """
                        UPDATE app.categories
                        SET name = repeat('x', 81)
                        """,
                """
                UPDATE app.monthly_budgets
                SET month_start = '2026-10-02'
                """,
                        """
                        UPDATE app.monthly_budgets
                        SET limit_minor = 0
                        """,
                """
                UPDATE app.category_budgets
                SET month_start = '2026-10-02'
                """,
                        """
                        UPDATE app.category_budgets
                        SET limit_minor = - 1
                        """,
                """
                UPDATE app.user_sync_state
                SET last_seq = - 1
                """
            })
    void financialConstraintsRejectInvalidRows(String sql) {
        assertThrows(DataAccessException.class, () -> asUser(userA, () -> jdbc.update(sql)));
    }

    @Test
    @DisplayName("БД соблюдает уникальность и int64")
    void uniquenessAndBigintBoundariesAreEnforced() {
        assertSqlState(
                "23505",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.categories(user_id, id, name, normalized_name)
                                                VALUES (?, ?, 'FOOD', 'food')
                                                """,
                                                userA,
                                                UUID.randomUUID())));
        assertSqlState(
                "23505",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.monthly_budgets(user_id, id, month_start, limit_minor)
                                                VALUES (?, ?, '2026-10-01', 1)
                                                """,
                                                userA,
                                                UUID.randomUUID())));
        assertSqlState(
                "23505",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO app.category_budgets(user_id, id, category_id, month_start, limit_minor)
                                                VALUES (?, ?, ?, '2026-10-01', 1)
                                                """,
                                                userA,
                                                UUID.randomUUID(),
                                                category)));
        assertSqlState(
                "23505",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO app.users(id, email, email_norm, password_hash, base_currency_code)
                                SELECT ?, email, email_norm, password_hash, base_currency_code
                                FROM app.users
                                WHERE id = ?
                                """,
                                UUID.randomUUID(),
                                userA));
        assertEquals(
                1,
                asUser(
                        userA,
                        () ->
                                jdbc.update(
                                        """
                                        UPDATE app.expenses
                                        SET amount_minor = ?
                                        """,
                                        Long.MAX_VALUE)));
        assertSqlState(
                "22003",
                () ->
                        asUser(
                                userA,
                                () ->
                                        jdbc.update(
                                                """
                                                UPDATE app.expenses
                                                SET amount_minor = 9223372036854775808
                                                """)));
    }

    @Test
    @DisplayName("Runtime не обходит RLS и не меняет историю")
    void runtimeCannotBypassRlsChangeSchemaOrRewriteHistory() {
        assertEquals(
                "finance_runtime",
                jdbc.queryForObject(
                        """
                        SELECT current_user
                        """,
                        String.class));
        assertFalse(
                jdbc.queryForObject(
                        """
                        SELECT rolsuper
                            OR rolbypassrls
                            OR rolcreaterole
                            OR rolcreatedb
                        FROM pg_roles
                        WHERE rolname = current_user
                        """,
                        Boolean.class));
        assertFalse(
                jdbc.queryForObject(
                        """
                        SELECT pg_has_role(current_user, 'finance_migrator', 'MEMBER')
                        """,
                        Boolean.class));
        assertEquals(
                8,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'app'
                            AND c.relrowsecurity
                            AND c.relforcerowsecurity
                            AND pg_get_userbyid(c.relowner) = 'finance_migrator'
                        """,
                        Integer.class));

        for (String sql :
                new String[] {
                    "CREATE TABLE app.forbidden(id int)",
                    "CREATE TABLE public.forbidden(id int)",
                    "CREATE TEMP TABLE forbidden(id int)",
                    "ALTER TABLE app.categories DISABLE ROW LEVEL SECURITY",
                    "TRUNCATE app.categories CASCADE",
                    """
                    SET ROLE finance_migrator
                    """,
                    """
                    SELECT *
                    FROM app.flyway_schema_history
                    """,
                    """
                    DELETE FROM app.sync_changes
                    """,
                    """
                    UPDATE app.sync_operations
                    SET result_payload = '{}'
                    """
                }) {
            assertSqlState("42501", () -> jdbc.execute(sql));
        }

        assertThrows(
                DataAccessException.class,
                () ->
                        transaction.execute(
                                status -> {
                                    jdbc.execute(
                                            """
                                            SET LOCAL row_security = off
                                            """);

                                    return jdbc.queryForObject(
                                            """
                                            SELECT count(*)
                                            FROM app.categories
                                            """,
                                            Integer.class);
                                }));
    }

    private static void assertSqlState(String expected, Runnable action) {
        DataAccessException error = assertThrows(DataAccessException.class, action::run);
        assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expected, ((SQLException) error.getMostSpecificCause()).getSQLState());
    }
}
