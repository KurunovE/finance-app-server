package com.financetracker.integration.controller.sync;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.financetracker.admin.InviteCommand;
import com.financetracker.repository.context.impl.DatabaseUserTransactionContext;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.support.ApplicationFixture;
import com.financetracker.support.json.SyncJson;
import com.financetracker.support.model.SyncTestAccount;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncApiTest {
    private static final ApplicationFixture APP = new ApplicationFixture();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc, owner;

    @BeforeAll
    static void start() {
        APP.start("--app.auth.requests-per-minute=10000");
        pool = APP.database.runtimePool();
        jdbc = new JdbcTemplate(pool);
        owner = new JdbcTemplate(APP.database.flyway().getConfiguration().getDataSource());
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }

        APP.close();
    }

    private HttpResponse<String> request(String method, String path, Object body, String access)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(APP.url + path)).timeout(Duration.ofSeconds(15));

        if (path.startsWith("/api/v1/sync/")) {
            builder.header(
                    "X-Recovery-Epoch",
                    APP.context.getBean(RecoveryState.class).current().epoch().toString());
        }

        if (access != null) {
            builder.header("Authorization", "Bearer " + access);
        }

        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        return HTTP.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                APP.context.getBean(SyncJson.class).write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> json(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode());

        return JSONObjectUtils.parse(response.body());
    }

    private SyncTestAccount account() throws Exception {
        var output = new ByteArrayOutputStream();
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
                new PrintStream(output));
        String email = UUID.randomUUID() + "@example.test";
        UUID device = UUID.randomUUID();
        assertEquals(
                201,
                request(
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
                                        device.toString(),
                                        "inviteCode",
                                        output.toString().strip()),
                                null)
                        .statusCode());
        APP.verifyEmail(email);
        var registered = json(request("POST", "/api/v1/auth/login", Map.of("email", email, "password", "silver garden waterfall evening", "deviceId", device.toString()), null), 200);
        String token = (String) registered.get("accessToken");
        UUID user = UUID.fromString(SignedJWT.parse(token).getJWTClaimsSet().getSubject());
        APP.bootstrap(token);
        var page = pull(token, 0, 200);
        var initial = maps(page.get("changes"));

        return SyncTestAccount.builder()
                .user(user)
                .access(token)
                .category(UUID.fromString((String) initial.getFirst().get("entityId")))
                .build();
    }

    private List<Map<String, Object>> maps(Object value) {
        var list = new ArrayList<Map<String, Object>>();

        for (Object item : (List<?>) value) {
            var map = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) item).forEach((key, val) -> map.put(key.toString(), val));
            list.add(map);
        }

        return list;
    }

    private Map<String, Object> op(
            UUID operation,
            String type,
            UUID id,
            String action,
            Long version,
            Map<String, ?> data) {
        var map = new LinkedHashMap<String, Object>();
        map.put("epoch", APP.context.getBean(RecoveryState.class).current().epoch().toString());
        map.put("operationId", operation.toString());
        map.put("entityType", type);
        map.put("entityId", id.toString());
        map.put("action", action);
        map.put("expectedVersion", version);
        map.put("data", data);

        return map;
    }

    private Map<String, Object> create(String type, UUID id, Map<String, ?> data) {
        return op(UUID.randomUUID(), type, id, "CREATE", null, data);
    }

    private Map<String, Object> push(SyncTestAccount user, Map<String, Object> operation)
            throws Exception {
        return maps(json(
                                request(
                                        "POST",
                                        "/api/v1/sync/push",
                                        Map.of("operations", List.of(operation)),
                                        user.access()),
                                200)
                        .get("results"))
                .getFirst();
    }

    private Map<String, Object> pull(String access, long cursor, int limit) throws Exception {
        return json(
                request(
                        "GET",
                        "/api/v1/sync/pull?cursor=" + cursor + "&limit=" + limit,
                        null,
                        access),
                200);
    }

    private Map<String, Object> expense(SyncTestAccount user, long amount) {
        return Map.of(
                "categoryId",
                user.category().toString(),
                "amountMinor",
                amount,
                "occurredOn",
                "2026-10-31");
    }

    private long count(SyncTestAccount user, String table) {
        return new TransactionTemplate(new DataSourceTransactionManager(pool))
                .execute(
                        status -> {
                            new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource())
                                    .setUser(user.user());

                            return jdbc.queryForObject(
                                    """
                                    SELECT count(*)
                                    FROM app.%s
                                    WHERE user_id = ?
                                    """
                                            .formatted(table),
                                    Long.class,
                                    user.user());
                        });
    }

    @Test
    @DisplayName("Повтор операции сохраняет исходную версию")
    void idempotencyUsesNormalizedContentAndPreservesOriginalVersion() throws Exception {
        SyncTestAccount a = account();
        UUID id = UUID.randomUUID(), operation = UUID.randomUUID();
        var data = new HashMap<String, Object>(expense(a, 125));
        data.put("note", "  Food  ");
        var command = op(operation, "EXPENSE", id, "CREATE", null, data);
        assertEquals("APPLIED", push(a, command).get("status"));
        data.put("note", "Food");
        var repeated = push(a, command);
        assertEquals("ALREADY_APPLIED", repeated.get("status"));
        assertEquals(1, ((Number) repeated.get("entityVersion")).intValue());
        assertEquals(1, count(a, "expenses"));
        assertEquals(4, count(a, "sync_changes"));
        assertEquals(1, count(a, "sync_operations"));
        data.put("amountMinor", 126);
        var rejected = json(request("POST", "/api/v1/sync/push", Map.of("operations", List.of(command)), a.access()), 409);
        assertEquals("OPERATION_ID_REUSED", rejected.get("code"));
        assertEquals(1, count(a, "expenses"));
    }

    @Test
    @DisplayName("Версии и tombstone защищают от повторного ID")
    void optimisticUpdatesDeleteTombstonesAndIdReuseAreSafe() throws Exception {
        SyncTestAccount a = account();
        UUID id = UUID.randomUUID();
        assertEquals("APPLIED", push(a, create("EXPENSE", id, expense(a, 10))).get("status"));
        assertEquals(
                "APPLIED",
                push(
                                a,
                                op(
                                        UUID.randomUUID(),
                                        "EXPENSE",
                                        id,
                                        "UPDATE",
                                        1L,
                                        Map.of("amountMinor", 20)))
                        .get("status"));
        var stale = push(a, op(UUID.randomUUID(), "EXPENSE", id, "UPDATE", 1L, Map.of("amountMinor", 30)));
        assertEquals("CONFLICT", stale.get("status"));
        assertEquals(2, ((Number) stale.get("entityVersion")).intValue());
        assertEquals(
                "APPLIED",
                push(a, op(UUID.randomUUID(), "EXPENSE", id, "DELETE", 2L, null)).get("status"));
        var tombstone = maps(pull(a.access(), 5, 200).get("changes")).getFirst();
        assertEquals("DELETE", tombstone.get("operation"));
        assertNull(tombstone.get("snapshot"));
        assertEquals(3, ((Number) tombstone.get("entityVersion")).intValue());
        assertEquals(0, count(a, "expenses"));
        assertEquals(
                "ENTITY_ID_REUSED", push(a, create("EXPENSE", id, expense(a, 10))).get("code"));
        assertEquals(
                "NOT_FOUND",
                push(a, op(UUID.randomUUID(), "EXPENSE", id, "DELETE", 2L, null)).get("status"));
    }

    @Test
    @DisplayName("Чужие ID не раскрывают snapshot")
    void foreignIdsAndCategoriesNeverRevealForeignSnapshots() throws Exception {
        SyncTestAccount a = account(), b = account();
        UUID foreign = UUID.randomUUID();
        push(b, create("EXPENSE", foreign, expense(b, 10)));
        var update = push(a, op(UUID.randomUUID(), "EXPENSE", foreign, "UPDATE", 1L, Map.of("amountMinor", 99)));
        assertEquals("NOT_FOUND", update.get("status"));
        assertFalse(update.containsKey("serverSnapshot"));
        assertEquals(
                "NOT_FOUND",
                push(a, op(UUID.randomUUID(), "EXPENSE", foreign, "DELETE", 1L, null))
                        .get("status"));
        assertEquals(
                "INVALID_CATEGORY",
                push(a, create("EXPENSE", UUID.randomUUID(), expense(b, 10))).get("code"));
        assertEquals(0, count(a, "expenses"));
        assertEquals(1, count(b, "expenses"));

        for (var change : maps(pull(a.access(), 0, 200).get("changes"))) {
            assertNotEquals(foreign.toString(), change.get("entityId"));
        }

        assertEquals(3, ((Number) pull(a.access(), 0, 200).get("nextCursor")).intValue());
    }

    @Test
    @DisplayName("Batch сохраняет отдельный результат операции")
    void validationFailuresAreRememberedAndBatchesCommitEachOperationSeparately() throws Exception {
        SyncTestAccount a = account();
        var invalid = create("EXPENSE", UUID.randomUUID(), expense(a, 0));
        assertEquals("VALIDATION_ERROR", push(a, invalid).get("status"));
        assertEquals("VALIDATION_ERROR", push(a, invalid).get("status"));
        assertEquals(1, count(a, "sync_operations"));
        assertEquals(3, count(a, "sync_changes"));
        UUID id = UUID.randomUUID();
        var first = create("EXPENSE", id, expense(a, 10));
        var conflict = op(UUID.randomUUID(), "EXPENSE", id, "UPDATE", 99L, Map.of("amountMinor", 20));
        var batch = maps(json(request("POST", "/api/v1/sync/push", Map.of("operations", List.of(first, conflict)), a.access()), 200).get("results"));
        assertEquals(
                List.of("APPLIED", "CONFLICT"), batch.stream().map(r -> r.get("status")).toList());
        assertEquals(1, count(a, "expenses"));
        assertEquals(4, count(a, "sync_changes"));
    }

    @Test
    @DisplayName("Гонки сохраняют один эффект и непрерывный seq")
    void concurrentCreateAndUpdatesHaveOneEffectAndContiguousSequence() throws Exception {
        SyncTestAccount a = account();
        UUID id = UUID.randomUUID();
        var create = create("EXPENSE", id, expense(a, 10));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Map<String, Object>>>of(() -> push(a, create), () -> push(a, create)));
            assertEquals(
                    Set.of("APPLIED", "ALREADY_APPLIED"),
                    Set.of(results.get(0).get().get("status"), results.get(1).get().get("status")));
            var updates = executor.invokeAll(List.<Callable<Map<String, Object>>>of(() -> push(a, op(UUID.randomUUID(), "EXPENSE", id, "UPDATE", 1L, Map.of("amountMinor", 20))), () -> push(a, op(UUID.randomUUID(), "EXPENSE", id, "UPDATE", 1L, Map.of("amountMinor", 30)))));
            assertEquals(
                    Set.of("APPLIED", "CONFLICT"),
                    Set.of(updates.get(0).get().get("status"), updates.get(1).get().get("status")));
        }

        assertEquals(1, count(a, "expenses"));
        assertEquals(5, count(a, "sync_changes"));
        var changes = maps(pull(a.access(), 0, 200).get("changes"));
        assertEquals(
                List.of(1L, 2L, 3L, 4L, 5L),
                changes.stream().map(c -> ((Number) c.get("seq")).longValue()).toList());
    }

    @Test
    @DisplayName("Ошибка журнала откатывает финансовое изменение")
    void rollbackAfterFinancialMutationBeforeJournalAllowsSafeRetry() throws Exception {
        SyncTestAccount a = account();
        UUID id = UUID.randomUUID();
        var command = create("EXPENSE", id, expense(a, 10));
        owner.execute(
                "CREATE FUNCTION app.test_sync_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.entity_id='"
                        + id
                        + "'::uuid THEN RAISE EXCEPTION 'injected journal failure'; END IF; RETURN NEW; END $$");
        owner.execute(
                "CREATE TRIGGER test_sync_failure BEFORE INSERT ON app.sync_changes FOR EACH ROW EXECUTE FUNCTION app.test_sync_failure()");

        try {
            assertEquals(
                    500,
                    request(
                                    "POST",
                                    "/api/v1/sync/push",
                                    Map.of("operations", List.of(command)),
                                    a.access())
                            .statusCode());
            assertEquals(0, count(a, "expenses"));
            assertEquals(0, count(a, "sync_operations"));
            assertEquals(3, count(a, "sync_changes"));
            assertEquals(3, ((Number) pull(a.access(), 0, 200).get("nextCursor")).intValue());
        } finally {
            owner.execute("DROP TRIGGER test_sync_failure ON app.sync_changes");
            owner.execute("DROP FUNCTION app.test_sync_failure()");
        }

        assertEquals("APPLIED", push(a, command).get("status"));
        assertEquals(1, count(a, "expenses"));
        assertEquals(4, count(a, "sync_changes"));
    }

    @Test
    @DisplayName("Пагинация не теряет события")
    void manyEventsPaginateWithoutLossAndNewEventsArriveAfterExistingCursor() throws Exception {
        SyncTestAccount a = account();
        var operations = new ArrayList<Map<String, Object>>();

        for (int i = 0; i < 50; i++) {
            operations.add(create("EXPENSE", UUID.randomUUID(), expense(a, i + 1)));
        }

        for (int i = 0; i < 2; i++) {
            assertEquals(
                    200,
                    request(
                                    "POST",
                                    "/api/v1/sync/push",
                                    Map.of(
                                            "operations",
                                            operations.stream()
                                                    .map(
                                                            op -> {
                                                                var copy = new LinkedHashMap<>(op);
                                                                copy.put(
                                                                        "operationId",
                                                                        UUID.randomUUID()
                                                                                .toString());
                                                                copy.put(
                                                                        "entityId",
                                                                        UUID.randomUUID()
                                                                                .toString());

                                                                return copy;
                                                            })
                                                    .toList()),
                                    a.access())
                            .statusCode());
        }

        var seen = new ArrayList<Long>();
        long cursor = 0;

        while (true) {
            var page = pull(a.access(), cursor, 1);
            var changes = maps(page.get("changes"));

            for (var change : changes) {
                seen.add(((Number) change.get("seq")).longValue());
            }

            cursor = ((Number) page.get("nextCursor")).longValue();

            if (!(Boolean) page.get("hasMore")) {
                break;
            }
        }

        assertEquals(103, seen.size());

        for (int i = 0; i < seen.size(); i++) {
            assertEquals(i + 1L, seen.get(i));
        }

        var next = create("CATEGORY", UUID.randomUUID(), Map.of("name", "New"));
        assertEquals("APPLIED", push(a, next).get("status"));
        var newPage = pull(a.access(), cursor, 1);
        assertEquals(104, ((Number) newPage.get("nextCursor")).intValue());
        assertTrue(maps(pull(a.access(), 104, 200).get("changes")).isEmpty());
        assertEquals(104, ((Number) pull(a.access(), 104, 200).get("nextCursor")).intValue());
    }

    @Test
    @DisplayName("Блокировка аккаунта не мешает другим")
    void oneLockedAccountDoesNotBlockOtherAccountsAndCategoryPrecedesExpense() throws Exception {
        SyncTestAccount a = account(), b = account();
        new TransactionTemplate(new DataSourceTransactionManager(pool))
                .executeWithoutResult(
                        status -> {
                            new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource())
                                    .setUser(a.user());
                            jdbc.queryForObject(
                                    """
                                    SELECT last_seq
                                    FROM app.user_sync_state
                                    WHERE user_id = ?
                                    FOR UPDATE
                                    """,
                                    Long.class,
                                    a.user());

                            try {
                                assertEquals(
                                        "APPLIED",
                                        push(
                                                        b,
                                                        create(
                                                                "EXPENSE",
                                                                UUID.randomUUID(),
                                                                expense(b, 10)))
                                                .get("status"));
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        });
        UUID category = UUID.randomUUID(), expense = UUID.randomUUID();
        var batch = List.of(create("CATEGORY", category, Map.of("name", "Travel")), create("EXPENSE", expense, Map.of("categoryId", category.toString(), "amountMinor", 1, "occurredOn", "2026-11-01")));
        assertEquals(
                200,
                request("POST", "/api/v1/sync/push", Map.of("operations", batch), a.access())
                        .statusCode());
        var changes = maps(pull(a.access(), 3, 200).get("changes"));
        assertEquals(
                List.of("CATEGORY", "EXPENSE"),
                changes.stream().map(c -> c.get("entityType")).toList());
    }

    @Test
    @DisplayName("Неверный sync-запрос не меняет данные")
    void boundsAndMalformedRequestsHaveNoEffects() throws Exception {
        SyncTestAccount a = account();
        var operation = create("EXPENSE", UUID.randomUUID(), expense(a, 1));
        assertEquals(
                400,
                request("POST", "/api/v1/sync/push", Map.of("operations", List.of()), a.access())
                        .statusCode());
        assertEquals(
                400,
                request(
                                "POST",
                                "/api/v1/sync/push",
                                Map.of("operations", Collections.nCopies(51, operation)),
                                a.access())
                        .statusCode());
        assertEquals(
                400,
                request(
                                "POST",
                                "/api/v1/sync/push",
                                Map.of("operations", Collections.singletonList(null)),
                                a.access())
                        .statusCode());
        assertEquals(
                422, request("GET", "/api/v1/sync/pull?cursor=-1", null, a.access()).statusCode());
        assertEquals(
                422, request("GET", "/api/v1/sync/pull?cursor=999", null, a.access()).statusCode());
        assertEquals(
                422, request("GET", "/api/v1/sync/pull?limit=201", null, a.access()).statusCode());
        assertEquals(
                400, request("GET", "/api/v1/sync/pull?cursor=bad", null, a.access()).statusCode());
        assertEquals(401, request("GET", "/api/v1/sync/pull", null, null).statusCode());
        assertEquals(
                413,
                request(
                                "POST",
                                "/api/v1/sync/push",
                                Map.of("padding", "x".repeat(256 * 1024)),
                                a.access())
                        .statusCode());
        Object deep = Map.of("x", 1);

        for (int i = 0; i < 10; i++) {
            deep = Map.of("nested", deep);
        }

        var malicious = new LinkedHashMap<>(operation);
        malicious.put("data", deep);
        assertEquals(
                400,
                request(
                                "POST",
                                "/api/v1/sync/push",
                                Map.of("operations", List.of(malicious)),
                                a.access())
                        .statusCode());
        assertEquals(0, count(a, "expenses"));
        assertEquals(0, count(a, "sync_operations"));
    }

    @Test
    @DisplayName("Два устройства согласуют конфликты и удаления")
    void twoDevicesRecoverThroughConflictsAndTombstonesWithoutCrossAccountLeaks() throws Exception {
        SyncTestAccount a = account(), b = account();
        String email = (String) json(request("GET", "/api/v1/me", null, a.access()), 200).get("email");
        UUID device = UUID.randomUUID();
        var second = json(request("POST", "/api/v1/auth/login", Map.of("email", email, "password", "silver garden waterfall evening", "deviceId", device.toString()), null), 200);
        String access = (String) second.get("accessToken");
        APP.bootstrap(access);
        UUID expense = UUID.randomUUID();
        var command = create("EXPENSE", expense, expense(a, 1234));
        push(a, command); // The client may lose this successful response.
        assertEquals("ALREADY_APPLIED", push(a, command).get("status"));
        var received = maps(pull(access, 3, 1).get("changes"));
        assertEquals(expense.toString(), received.getFirst().get("entityId"));
        var secondAccount = SyncTestAccount.builder().user(a.user()).access(access).category(a.category()).build();
        assertEquals(
                "APPLIED",
                push(
                                secondAccount,
                                op(
                                        UUID.randomUUID(),
                                        "EXPENSE",
                                        expense,
                                        "UPDATE",
                                        1L,
                                        Map.of("note", "Device two")))
                        .get("status"));
        assertEquals(
                "CONFLICT",
                push(
                                a,
                                op(
                                        UUID.randomUUID(),
                                        "EXPENSE",
                                        expense,
                                        "UPDATE",
                                        1L,
                                        Map.of("note", "Device one")))
                        .get("status"));
        assertEquals(
                "APPLIED",
                push(a, op(UUID.randomUUID(), "EXPENSE", expense, "DELETE", 2L, null))
                        .get("status"));
        var tombstones = maps(pull(access, 5, 200).get("changes"));
        assertEquals("DELETE", tombstones.getFirst().get("operation"));
        assertEquals(3, maps(pull(b.access(), 0, 200).get("changes")).size());
        assertEquals(204, request("POST", "/api/v1/auth/logout", null, access).statusCode());
        assertEquals(401, request("GET", "/api/v1/sync/pull", null, access).statusCode());
        assertEquals(200, request("GET", "/api/v1/me", null, a.access()).statusCode());
        var reentered = json(request("POST", "/api/v1/auth/login", Map.of("email", email, "password", "silver garden waterfall evening", "deviceId", device.toString()), null), 200);
        assertEquals(
                6, maps(pull((String) reentered.get("accessToken"), 0, 200).get("changes")).size());
    }
}
