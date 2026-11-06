package com.financetracker.integration.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.financetracker.repository.context.impl.DatabaseUserTransactionContext;
import com.financetracker.support.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationTest {
    @Test
    @DisplayName("Upgrade V1 сохраняет данные и отзывает сессии")
    void upgradesPopulatedV1WithoutLosingFinancialDataAndRevokesLegacySessions() {
        try (PostgresFixture database = new PostgresFixture()) {
            database.start();
            Flyway.configure()
                    .configuration(database.flyway().getConfiguration())
                    .target("1")
                    .load()
                    .migrate();

            try (var pool = database.runtimePool()) {
                var jdbc = new JdbcTemplate(pool);
                UUID user = UUID.randomUUID(), session = UUID.randomUUID(), category = UUID.randomUUID();
                new TransactionTemplate(new DataSourceTransactionManager(pool))
                        .executeWithoutResult(
                                status -> {
                                    jdbc.update(
                                            """
                                            INSERT INTO app.users(id, email, email_norm, password_hash, base_currency_code)
                                            VALUES (?, 'upgrade@example.test', 'upgrade@example.test', 'test-hash', 'RUB')
                                            """,
                                            user);
                                    jdbc.update(
                                            """
                                            INSERT INTO app.auth_sessions(id, user_id, access_token_hash, refresh_token_hash, access_expires_at, refresh_expires_at)
                                            VALUES (?, ?, repeat('a', 64), repeat('b', 64), now() + interval '15 minutes', now() + interval '30 days')
                                            """,
                                            session,
                                            user);
                                    jdbc.update(
                                            """
                                            INSERT INTO app.used_refresh_tokens(token_hash, session_id, expires_at)
                                            VALUES (repeat('c', 64), ?, now() + interval '30 days')
                                            """,
                                            session);
                                    new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource())
                                            .setUser(user);
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
                                            VALUES (?, ?, ?, 123, '2026-10-31')
                                            """,
                                            user,
                                            UUID.randomUUID(),
                                            category);
                                });
                assertEquals(3, database.flyway().migrate().migrationsExecuted);
                assertEquals(0, database.flyway().migrate().migrationsExecuted);
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM app.auth_sessions
                                WHERE family_id = ?
                                    AND revoked_at IS NOT NULL
                                    AND device_id = ?
                                """,
                                Integer.class,
                                session,
                                session));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM app.used_refresh_tokens
                                WHERE family_id = ?
                                """,
                                Integer.class,
                                session));
                new TransactionTemplate(new DataSourceTransactionManager(pool))
                        .executeWithoutResult(
                                status -> {
                                    new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource())
                                            .setUser(user);
                                    assertEquals(
                                            123,
                                            jdbc.queryForObject(
                                                    """
                                                    SELECT amount_minor
                                                    FROM app.expenses
                                                    WHERE user_id = ?
                                                    """,
                                                    Integer.class,
                                                    user));
                                });
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM information_schema.columns
                                WHERE table_schema = 'app'
                                    AND table_name = 'auth_sessions'
                                    AND column_name = 'access_token_hash'
                                """,
                                Integer.class));
            }
        }
    }

    @Test
    @DisplayName("Миграции пустой БД повторно не выполняются")
    void migratesEmptyDatabaseAndRepeatsWithoutSeeding() {
        try (PostgresFixture database = new PostgresFixture()) {
            database.start();
            assertEquals(4, database.flyway().migrate().migrationsExecuted);
            assertEquals(0, database.flyway().migrate().migrationsExecuted);

            try (var pool = database.runtimePool()) {
                var jdbc = new JdbcTemplate(pool);
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM app.users
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
                assertEquals(
                        15,
                        jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM information_schema.tables
                                WHERE table_schema = 'app'
                                    AND table_name <> 'flyway_schema_history'
                                """,
                                Integer.class));
                var owner = new JdbcTemplate(database.flyway().getConfiguration().getDataSource());
                assertEquals(
                        "finance_migrator",
                        owner.queryForObject(
                                """
                                SELECT current_user
                                """,
                                String.class));
                UUID user = UUID.randomUUID();
                new TransactionTemplate(new DataSourceTransactionManager(pool))
                        .executeWithoutResult(
                                status -> {
                                    jdbc.update(
                                            """
                                            INSERT INTO app.users(id, email, email_norm, password_hash, base_currency_code)
                                            VALUES (?, 'owner-test@example.test', 'owner-test@example.test', 'test-hash', 'RUB')
                                            """,
                                            user);
                                    new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource())
                                            .setUser(user);
                                    jdbc.update(
                                            """
                                            INSERT INTO app.categories(user_id, id, name, normalized_name)
                                            VALUES (?, ?, 'Food', 'food')
                                            """,
                                            user,
                                            UUID.randomUUID());
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
                        0,
                        owner.queryForObject(
                                """
                                SELECT count(*)
                                FROM app.categories
                                """,
                                Integer.class));
            }
        }
    }

    @Test
    @DisplayName("Новые имена миграций совместимы с history")
    void validatesAlreadyAppliedUnpaddedMigrationNamesWithoutRepair() throws Exception {
        var legacy = Files.createTempDirectory(Path.of("build"), "legacy-migrations-");

        for (String name :
                List.of(
                        "V001__initial_schema.sql",
                        "V002__invites_jwt_families.sql",
                        "V003__email_verification_and_reset.sql",
                        "V004__recovery_epochs.sql")) {
            try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
                Assertions.assertNotNull(input);
                Files.write(legacy.resolve(name.replaceFirst("V00", "V")), input.readAllBytes());
            }
        }

        try (var database = new PostgresFixture()) {
            database.start();
            var original = Flyway.configure().configuration(database.flyway().getConfiguration()).locations("filesystem:" + legacy.toAbsolutePath()).load();
            assertEquals(4, original.migrate().migrationsExecuted);
            var jdbc = new JdbcTemplate(database.flyway().getConfiguration().getDataSource());
            var before = jdbc.queryForList(
                    """
            SELECT version, script, checksum
            FROM app.flyway_schema_history
            ORDER BY installed_rank
            """);
            database.flyway().validate();
            assertEquals(0, database.flyway().migrate().migrationsExecuted);
            assertEquals(
                    before,
                    jdbc.queryForList(
                            """
                            SELECT version, script, checksum
                            FROM app.flyway_schema_history
                            ORDER BY installed_rank
                            """));
        }
    }
}
