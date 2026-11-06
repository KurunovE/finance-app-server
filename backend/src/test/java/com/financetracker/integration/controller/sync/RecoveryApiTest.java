package com.financetracker.integration.controller.sync;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.financetracker.admin.InviteCommand;
import com.financetracker.admin.RecoveryCommand;
import com.financetracker.config.RecoveryBootstrap;
import com.financetracker.policy.SyncPolicy;
import com.financetracker.repository.sync.impl.JdbcRecoveryRepository;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.service.sync.impl.DefaultRecoveryState;
import com.financetracker.support.ApplicationFixture;
import com.financetracker.support.json.SyncJson;
import com.financetracker.support.model.RecoveryTestAccount;
import com.nimbusds.jose.util.JSONObjectUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryApiTest {
    static final ApplicationFixture APP = new ApplicationFixture();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static JdbcTemplate owner;

    @BeforeAll
    static void start() {
        APP.start("--app.auth.requests-per-minute=10000");
        owner = new JdbcTemplate(APP.database.flyway().getConfiguration().getDataSource());
    }

    @AfterAll
    static void stop() {
        APP.close();
    }

    String epoch() {
        return APP.context.getBean(RecoveryState.class).current().epoch().toString();
    }

    HttpResponse<String> request(
            String method, String path, Object body, String access, String epoch) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(APP.url + path));

        if (access != null) {
            b.header("Authorization", "Bearer " + access);
        }

        if (epoch != null) {
            b.header("X-Recovery-Epoch", epoch);
        }

        if (body != null) {
            b.header("Content-Type", "application/json");
        }

        return HTTP.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                APP.context.getBean(SyncJson.class).write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> call(
            String method, String path, Object body, String access, String epoch, int expected)
            throws Exception {
        var r = request(method, path, body, access, epoch);
        assertEquals(expected, r.statusCode(), () -> path + ": " + r.body());

        return r.body().isBlank() ? Map.of() : JSONObjectUtils.parse(r.body());
    }

    RecoveryTestAccount account() throws Exception {
        var out = new ByteArrayOutputStream();
        InviteCommand.run(
                new String[] {"create-invite"},
                Map.of(
                        "INVITE_DB_URL",
                        APP.database.database.getJdbcUrl(),
                        "INVITE_DB_USER",
                        "finance_invite_admin",
                        "INVITE_DB_PASSWORD",
                        APP.database.invitePassword),
                Clock.systemUTC(),
                new PrintStream(out));
        String email = UUID.randomUUID() + "@example.test";
        UUID device = UUID.randomUUID();
        call(
                "POST",
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        email,
                        "password",
                        "silver garden waterfall evening",
                        "baseCurrencyCode",
                        "RUB",
                        "deviceId",
                        device,
                        "inviteCode",
                        out.toString().strip()),
                null,
                null,
                201);
        APP.verifyEmail(email);

        return login(email, device);
    }

    RecoveryTestAccount login(String email, UUID device) throws Exception {
        return RecoveryTestAccount.builder()
                .email(email)
                .device(device)
                .access(
                        (String)
                                call(
                                                "POST",
                                                "/api/v1/auth/login",
                                                Map.of(
                                                        "email",
                                                        email,
                                                        "password",
                                                        "silver garden waterfall evening",
                                                        "deviceId",
                                                        device),
                                                null,
                                                null,
                                                200)
                                        .get("accessToken"))
                .build();
    }

    Map<String, Object> operation(String epoch) {
        return Map.of(
                "epoch",
                epoch,
                "operationId",
                UUID.randomUUID(),
                "entityType",
                "CATEGORY",
                "entityId",
                UUID.randomUUID(),
                "action",
                "CREATE",
                "data",
                Map.of("name", "Recovered"));
    }

    Map<String, Object> ack(long cursor, boolean preserved) {
        return Map.of(
                "lastCursor",
                cursor,
                "localCopyPreserved",
                preserved,
                "pendingOperationsQuarantined",
                true);
    }

    @Test
    @DisplayName("Replay нельзя пропустить или подтвердить заранее")
    void fullReplayCannotSkipPagesOrAcknowledgeWithoutPreservingLocalCopy() throws Exception {
        var a = account();
        String ep = epoch();
        call("GET", "/api/v1/sync/pull", null, a.access(), null, 428);
        call("GET", "/api/v1/sync/pull", null, a.access(), "invalid", 400);
        call("GET", "/api/v1/sync/pull", null, a.access(), UUID.randomUUID().toString(), 409);
        call("GET", "/api/v1/sync/pull", null, a.access(), ep, 409);
        assertEquals(
                true,
                call("GET", "/api/v1/sync/status", null, a.access(), null, 200)
                        .get("requiresFullResync"));
        var start = call("POST", "/api/v1/sync/recovery/start", null, a.access(), ep, 200);
        assertEquals(start, call("POST", "/api/v1/sync/recovery/start", null, a.access(), ep, 200));
        String path = "/api/v1/sync/recovery/" + start.get("recoveryId");
        call("POST", path + "/ack", ack(3, true), a.access(), ep, 409);
        call("GET", path + "/pull?cursor=1", null, a.access(), ep, 409);
        call("GET", path + "/pull?cursor=-1", null, a.access(), ep, 422);
        call("GET", path + "/pull?limit=201", null, a.access(), ep, 422);
        var first = call("GET", path + "/pull?limit=1", null, a.access(), ep, 200);
        assertEquals(true, first.get("hasMore"));
        call("POST", path + "/ack", ack(1, true), a.access(), ep, 409);
        assertEquals(first, call("GET", path + "/pull?limit=1", null, a.access(), ep, 200));
        var second = login(a.email(), UUID.randomUUID());
        APP.bootstrap(second.access());
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(operation(ep))),
                second.access(),
                ep,
                200);
        var page = call("GET", path + "/pull?cursor=1", null, a.access(), ep, 200);
        assertEquals(3, ((Number) page.get("nextCursor")).intValue());
        assertEquals(false, page.get("hasMore"));
        call("GET", path + "/pull", null, second.access(), ep, 404);
        var foreign = account();
        call("GET", path + "/pull", null, foreign.access(), ep, 404);
        call("POST", path + "/ack", ack(3, false), a.access(), ep, 400);
        call("POST", path + "/ack", ack(3, true), a.access(), ep, 204);
        call("POST", path + "/ack", ack(3, true), a.access(), ep, 204);
        assertEquals(
                false,
                call("GET", "/api/v1/sync/status", null, a.access(), null, 200)
                        .get("requiresFullResync"));
        assertEquals(
                4,
                ((Number)
                                call("GET", "/api/v1/sync/pull?cursor=3", null, a.access(), ep, 200)
                                        .get("nextCursor"))
                        .intValue());
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(operation(UUID.randomUUID().toString()))),
                a.access(),
                ep,
                409);
    }

    Map<String, String> admin() {
        return Map.of(
                "RECOVERY_DB_URL",
                APP.database.database.getJdbcUrl(),
                "RECOVERY_DB_USER",
                "finance_migrator",
                "RECOVERY_DB_PASSWORD",
                APP.database.migratorPassword);
    }

    Map<String, Object> command(String... args) throws Exception {
        var out = new ByteArrayOutputStream();
        RecoveryCommand.run(args, admin(), new PrintStream(out));

        return JSONObjectUtils.parse(out.toString());
    }

    @Test
    @DisplayName("Recovery требует активации и отзывает доступ")
    void adminRecoveryRevokesOldCredentialsAndRequiresExplicitActivation() throws Exception {
        var a = account();
        String before = APP.bootstrap(a.access());
        var old = operation(before);
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(old)),
                a.access(),
                before,
                200);
        var prepared = command("recovery-prepare");
        String next = (String) prepared.get("epoch");
        assertNotEquals(before, next);
        assertEquals("RECOVERY", prepared.get("phase"));

        try {
            call("GET", "/api/v1/me", null, a.access(), null, 503);
            call(
                    "POST",
                    "/api/v1/auth/login",
                    Map.of(
                            "email",
                            a.email(),
                            "password",
                            "silver garden waterfall evening",
                            "deviceId",
                            a.device()),
                    null,
                    null,
                    503);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> command("recovery-activate", "--epoch", next));
            assertThrows(
                    SQLException.class,
                    () ->
                            command(
                                    "recovery-activate",
                                    "--epoch",
                                    UUID.randomUUID().toString(),
                                    "--reconciled"));
            assertEquals("RECOVERY", command("recovery-status").get("phase"));
        } finally {
            command("recovery-activate", "--epoch", next, "--reconciled");
        }

        call("GET", "/api/v1/me", null, a.access(), null, 401);
        var fresh = login(a.email(), a.device());
        call("GET", "/api/v1/sync/pull", null, fresh.access(), before, 409);
        call("GET", "/api/v1/sync/pull", null, fresh.access(), next, 409);
        APP.bootstrap(fresh.access());
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(old)),
                fresh.access(),
                next,
                409);
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(operation(next))),
                fresh.access(),
                next,
                200);
    }

    @Test
    @DisplayName("Runtime не меняет эпоху восстановления")
    void runtimeCannotControlEpochAndMissingMetadataStartsInMaintenance() throws Exception {
        try (var pool = APP.database.runtimePool()) {
            var runtime = new JdbcTemplate(pool);
            assertThrows(
                    DataAccessException.class,
                    () ->
                            runtime.execute(
                                    """
                                    SELECT app.begin_recovery()
                                    """));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            runtime.execute(
                                    """
                                    UPDATE app.recovery_state
                                    SET phase = 'READY'
                                    """));
            assertEquals(
                    0,
                    runtime.queryForObject(
                            """
                            SELECT count(*)
                            FROM app.sync_recoveries
                            """,
                            Integer.class));
        }

        String before = epoch();
        owner.update(
                """
                DELETE FROM app.recovery_state
                """);
        APP.context.getBean(RecoveryBootstrap.class).afterPropertiesSet();
        String next = epoch();
        assertNotEquals(before, next);
        assertEquals("RECOVERY", command("recovery-status").get("phase"));
        command("recovery-activate", "--epoch", next, "--reconciled");
        assertThrows(
                IllegalStateException.class,
                () -> APP.context.getBean(RecoveryState.class).fence());
    }

    @Test
    @DisplayName("Откат дампа блокирует старые операции")
    void realOlderDumpForcesMaintenanceAndCannotReplayPreviouslyAcknowledgedWork()
            throws Exception {
        var a = account();
        String before = APP.bootstrap(a.access());
        var dump = APP.database.database.execInContainer("pg_dump", "-U", "finance_bootstrap", "-d", APP.database.database.getDatabaseName(), "--format=custom", "--exclude-table-data=app.recovery_state", "--exclude-table-data=app.sync_recoveries", "--file=/tmp/recovery-test.dump");
        assertEquals(0, dump.getExitCode());
        var acknowledged = operation(before);
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(acknowledged)),
                a.access(),
                before,
                200);
        assertEquals(
                4,
                ((Number)
                                call("GET", "/api/v1/sync/status", null, a.access(), null, 200)
                                        .get("latestSeq"))
                        .intValue());
        APP.context.close();

        try {
            var restore = APP.database.database.execInContainer("pg_restore", "-U", "finance_bootstrap", "-d", APP.database.database.getDatabaseName(), "--clean", "--if-exists", "--exit-on-error", "/tmp/recovery-test.dump");
            assertEquals(0, restore.getExitCode());
        } finally {
            APP.start("--app.auth.requests-per-minute=10000");
        }

        owner = new JdbcTemplate(APP.database.flyway().getConfiguration().getDataSource());
        String after = epoch();
        assertNotEquals(before, after);
        assertEquals("RECOVERY", command("recovery-status").get("phase"));
        call("GET", "/api/v1/me", null, a.access(), null, 503);
        command("recovery-activate", "--epoch", after, "--reconciled");
        call("GET", "/api/v1/me", null, a.access(), null, 401);
        var fresh = login(a.email(), a.device());
        var status = call("GET", "/api/v1/sync/status", null, fresh.access(), null, 200);
        assertEquals(3, ((Number) status.get("latestSeq")).intValue());
        assertEquals(true, status.get("requiresFullResync"));
        APP.bootstrap(fresh.access());
        call(
                "POST",
                "/api/v1/sync/push",
                Map.of("operations", List.of(acknowledged)),
                fresh.access(),
                after,
                409);
        assertEquals(
                3,
                ((Number)
                                call("GET", "/api/v1/sync/status", null, fresh.access(), null, 200)
                                        .get("latestSeq"))
                        .intValue());
    }

    @Test
    @DisplayName("Смена эпохи ждёт завершения writer")
    void writerFencePreventsAdminEpochChangeUntilTransactionCompletes() {
        try (var pool = APP.database.runtimePool()) {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
            var runtime = new DefaultRecoveryState(new JdbcRecoveryRepository(new JdbcTemplate(pool)));
            tx.executeWithoutResult(
                    status -> {
                        runtime.fence();
                        assertEquals(
                                false,
                                owner.queryForObject(
                                        """
                                        SELECT pg_try_advisory_xact_lock(?)
                                        """,
                                        Boolean.class,
                                        SyncPolicy.RECOVERY_LOCK_KEY));
                    });
            assertEquals(
                    true,
                    owner.queryForObject(
                            """
                            SELECT pg_try_advisory_xact_lock(?)
                            """,
                            Boolean.class,
                            SyncPolicy.RECOVERY_LOCK_KEY));
        }
    }
}
