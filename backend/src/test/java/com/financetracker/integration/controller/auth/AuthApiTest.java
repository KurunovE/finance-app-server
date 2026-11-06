package com.financetracker.integration.controller.auth;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.financetracker.admin.InviteCommand;
import com.financetracker.config.guard.DatabaseRoleGuard;
import com.financetracker.repository.context.impl.DatabaseUserTransactionContext;
import com.financetracker.security.token.CryptoTokens;
import com.financetracker.security.token.JwtTokens;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.support.ApplicationFixture;
import com.financetracker.support.model.AuthTestAccount;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.zaxxer.hikari.HikariDataSource;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthApiTest {
    private static final ApplicationFixture APP = new ApplicationFixture();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate admin;
    private static Map<String, String> adminEnvironment;
    private static final String PASSWORD = "violet river lantern meadow";

    @BeforeAll
    static void start() {
        APP.start("--app.auth.requests-per-minute=10000");
        pool = APP.database.runtimePool();
        jdbc = new JdbcTemplate(pool);
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                APP.database.database.getJdbcUrl(),
                                "finance_invite_admin",
                                APP.database.invitePassword));
        adminEnvironment =
                Map.of(
                        "INVITE_DB_URL",
                        APP.database.database.getJdbcUrl(),
                        "INVITE_DB_USER",
                        "finance_invite_admin",
                        "INVITE_DB_PASSWORD",
                        APP.database.invitePassword);
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }

        APP.close();
    }

    private String invite(String... options) throws Exception {
        return inviteAt(Clock.systemUTC(), options);
    }

    private String inviteAt(Clock clock, String... options) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var args = new ArrayList<>(List.of("create-invite"));
        args.addAll(List.of(options));
        InviteCommand.run(
                args.toArray(String[]::new), adminEnvironment, clock, new PrintStream(bytes));

        return bytes.toString(StandardCharsets.UTF_8).strip();
    }

    private HttpResponse<String> request(
            String method, String path, Map<String, ?> data, String access) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(APP.url + path)).timeout(Duration.ofSeconds(20));

        if (access != null) {
            builder.header("Authorization", "Bearer " + access);
        }

        if (data != null) {
            builder.header("Content-Type", "application/json");
        }

        return HTTP.send(
                builder.method(
                                method,
                                data == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                JSONObjectUtils.toJSONString(data)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> json(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());

        return response.body().isBlank() ? Map.of() : JSONObjectUtils.parse(response.body());
    }

    private Map<String, Object> registerBody(
            String email, UUID device, String code, String password) {
        return Map.of(
                "email",
                email,
                "password",
                password,
                "deviceId",
                device.toString(),
                "inviteCode",
                code,
                "baseCurrencyCode",
                "RUB");
    }

    private AuthTestAccount account() throws Exception {
        String email = UUID.randomUUID() + "@example.test", code = invite();
        UUID device = UUID.randomUUID();
        assertEquals(
                201,
                request(
                                "POST",
                                "/api/v1/auth/register",
                                registerBody(email, device, code, PASSWORD),
                                null)
                        .statusCode());
        APP.verifyEmail(email);
        var result = json(login(email, PASSWORD, device), 200);
        String access = (String) result.get("accessToken");

        return AuthTestAccount.builder()
                .email(email)
                .device(device)
                .invite(code)
                .access(access)
                .refresh((String) result.get("refreshToken"))
                .user(UUID.fromString(SignedJWT.parse(access).getJWTClaimsSet().getSubject()))
                .build();
    }

    private HttpResponse<String> refresh(String token) throws Exception {
        return request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", token), null);
    }

    private HttpResponse<String> login(String email, String password, UUID device)
            throws Exception {
        return request(
                "POST",
                "/api/v1/auth/login",
                Map.of("email", email, "password", password, "deviceId", device.toString()),
                null);
    }

    private <T> T asUser(UUID user, Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(pool))
                .execute(
                        status -> {
                            new DatabaseUserTransactionContext(jdbc, jdbc.getDataSource())
                                    .setUser(user);

                            return work.get();
                        });
    }

    @Test
    @DisplayName("Регистрация создаёт свои данные и хеши")
    void registrationSeedsOnlyOwnDataAndStoresHashes() throws Exception {
        AuthTestAccount a = account(), b = account();
        var profile = json(request("GET", "/api/v1/me", null, a.access()), 200);
        assertEquals(a.user().toString(), profile.get("id"));
        assertEquals(a.email(), profile.get("email"));
        assertEquals(Set.of("id", "email", "baseCurrencyCode"), profile.keySet());
        asUser(
                a.user(),
                () -> {
                    assertEquals(
                            3,
                            jdbc.queryForObject(
                                    """
                                    SELECT count(*)
                                    FROM app.categories
                                    """,
                                    Integer.class));
                    assertEquals(
                            3,
                            jdbc.queryForObject(
                                    """
                                    SELECT last_seq
                                    FROM app.user_sync_state
                                    """,
                                    Integer.class));
                    assertEquals(
                            List.of(1L, 2L, 3L),
                            jdbc.queryForList(
                                    """
                                    SELECT seq
                                    FROM app.sync_changes
                                    ORDER BY seq
                                    """,
                                    Long.class));
                    assertEquals(
                            a.user(),
                            jdbc.queryForObject(
                                    """
                                    SELECT DISTINCT user_id
                                    FROM app.sync_changes
                                    """,
                                    UUID.class));

                    return null;
                });
        assertNotEquals(a.user(), b.user());
        assertEquals(
                1,
                admin.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.registration_invites
                        WHERE code_hash = ?
                            AND used_at IS NOT NULL
                            AND used_by = ?
                        """,
                        Integer.class,
                        CryptoTokens.hash(a.invite()),
                        a.user()));
        assertEquals(
                CryptoTokens.hash(a.refresh()),
                jdbc.queryForObject(
                        """
                        SELECT refresh_token_hash
                        FROM app.auth_sessions
                        WHERE user_id = ?
                        """,
                        String.class,
                        a.user()));
        assertTrue(
                jdbc.queryForObject(
                                """
                                SELECT password_hash
                                FROM app.users
                                WHERE id = ?
                                """,
                                String.class,
                                a.user())
                        .startsWith("{argon2id}$argon2id$"));
        assertEquals(
                60,
                jdbc.queryForObject(
                        """
                        SELECT round(extract(epoch FROM(refresh_expires_at - created_at)) / 86400)
                        FROM app.auth_sessions
                        WHERE user_id = ?
                        """,
                        Integer.class,
                        a.user()));
    }

    @Test
    @DisplayName("Ошибка регистрации не расходует приглашение")
    void inviteValidationAndDuplicateEmailRollBackConsumption() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        UUID device = UUID.randomUUID();
        String expired = inviteAt(Clock.offset(Clock.systemUTC(), Duration.ofDays(-8)));

        for (String code : List.of("bad", CryptoTokens.random(16), expired)) {
            json(
                    request(
                            "POST",
                            "/api/v1/auth/register",
                            registerBody(email, device, code, PASSWORD),
                            null),
                    422);
        }

        AuthTestAccount a = account();
        String reusable = invite();
        json(
                request(
                        "POST",
                        "/api/v1/auth/register",
                        registerBody(a.email(), device, reusable, PASSWORD),
                        null),
                409);
        assertNull(
                admin.queryForObject(
                        """
                        SELECT used_at
                        FROM app.registration_invites
                        WHERE code_hash = ?
                        """,
                        Timestamp.class,
                        CryptoTokens.hash(reusable)));
        json(
                request(
                        "POST",
                        "/api/v1/auth/register",
                        registerBody(email, device, reusable, PASSWORD),
                        null),
                201);
        json(
                request(
                        "POST",
                        "/api/v1/auth/register",
                        registerBody(
                                UUID.randomUUID() + "@example.test", device, reusable, PASSWORD),
                        null),
                422);
    }

    @Test
    @DisplayName("Приглашение используется один раз при гонке")
    void concurrentRegistrationCanConsumeInviteOnlyOnce() throws Exception {
        String code = invite();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.<Callable<Integer>>of(() -> request("POST", "/api/v1/auth/register", registerBody(UUID.randomUUID() + "@example.test", UUID.randomUUID(), code, PASSWORD), null).statusCode(), () -> request("POST", "/api/v1/auth/register", registerBody(UUID.randomUUID() + "@example.test", UUID.randomUUID(), code, PASSWORD), null).statusCode()));
            var results = List.of(futures.get(0).get(), futures.get(1).get());
            assertTrue(results.contains(201));
            assertTrue(results.contains(422));
        }
    }

    @Test
    @DisplayName("Гонка email создаёт один аккаунт")
    void sameEmailConcurrentRegistrationCreatesOneAccountAndPreservesUnusedInvite()
            throws Exception {
        String email = UUID.randomUUID() + "@example.test", first = invite(), second = invite();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(() -> request("POST", "/api/v1/auth/register", registerBody(email, UUID.randomUUID(), first, PASSWORD), null).statusCode(), () -> request("POST", "/api/v1/auth/register", registerBody(email.toUpperCase(Locale.ROOT), UUID.randomUUID(), second, PASSWORD), null).statusCode()));
            assertEquals(Set.of(201, 409), Set.of(results.get(0).get(), results.get(1).get()));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            """
                            SELECT count(*)
                            FROM app.users
                            WHERE email_norm = ?
                            """,
                            Integer.class,
                            email));
            assertEquals(
                    1,
                    admin.queryForObject(
                            """
                            SELECT count(*)
                            FROM app.registration_invites
                            WHERE code_hash IN (?, ?)
                                AND used_at IS NULL
                            """,
                            Integer.class,
                            CryptoTokens.hash(first),
                            CryptoTokens.hash(second)));
        }
    }

    @Test
    @DisplayName("Вход нормализует email и заменяет семью")
    void loginNormalizesEmailAndNewLoginRevokesPreviousDeviceFamily() throws Exception {
        AuthTestAccount a = account();
        var tokens = json(login(a.email().toUpperCase(Locale.ROOT), PASSWORD, a.device()), 200);
        assertEquals(401, request("GET", "/api/v1/me", null, a.access()).statusCode());
        assertEquals(401, refresh(a.refresh()).statusCode());
        assertEquals(
                200,
                request("GET", "/api/v1/me", null, (String) tokens.get("accessToken"))
                        .statusCode());
        assertEquals(900, ((Number) tokens.get("expiresIn")).intValue());
        var other = json(login(a.email(), PASSWORD, UUID.randomUUID()), 200);
        assertEquals(
                200,
                request("GET", "/api/v1/me", null, (String) tokens.get("accessToken"))
                        .statusCode());
        assertEquals(
                200,
                request("GET", "/api/v1/me", null, (String) other.get("accessToken")).statusCode());
    }

    @Test
    @DisplayName("Вход обновляет устаревший Argon2id")
    void successfulLoginUpgradesAnOlderArgon2WorkFactor() throws Exception {
        AuthTestAccount a = account();
        String weak = "{argon2id}" + new Argon2PasswordEncoder(16, 32, 1, 8192, 1).encode(PASSWORD);
        jdbc.update(
                """
                UPDATE app.users
                SET password_hash = ?
                WHERE id = ?
                """,
                weak,
                a.user());
        assertEquals(200, login(a.email(), PASSWORD, a.device()).statusCode());
        String upgraded = jdbc.queryForObject(
                """
        SELECT password_hash
        FROM app.users
        WHERE id = ?
        """,
                String.class,
                a.user());
        assertTrue(upgraded.contains("m=19456,t=2,p=1"));
        assertNotEquals(weak, upgraded);
    }

    @Test
    @DisplayName("Истёкший refresh и чужой did отклоняются")
    void expiredRefreshRevokesFamilyAndAnAccessJwtWithMismatchedDeviceIsRejected()
            throws Exception {
        AuthTestAccount a = account();
        var jwt = APP.context.getBean(JwtTokens.class);
        UUID jti = UUID.fromString(SignedJWT.parse(a.access()).getJWTClaimsSet().getJWTID());
        String wrongDevice = jwt.issue(a.user(), UUID.randomUUID(), jti, APP.context.getBean(RecoveryState.class).current().epoch());
        assertEquals(401, request("GET", "/api/v1/me", null, wrongDevice).statusCode());
        jdbc.update(
                """
                UPDATE app.auth_sessions
                SET created_at = now() - interval '62 days',
                    access_expires_at = now() - interval '61 days',
                    refresh_expires_at = now() - interval '1 day'
                WHERE user_id = ?
                """,
                a.user());
        assertEquals(401, request("GET", "/api/v1/me", null, a.access()).statusCode());
        assertEquals(401, refresh(a.refresh()).statusCode());
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.auth_sessions
                        WHERE user_id = ?
                            AND revoked_at IS NOT NULL
                        """,
                        Integer.class,
                        a.user()));
    }

    @Test
    @DisplayName("DISABLED выигрывает гонку с refresh")
    void disablingConcurrentlyWithRefreshCannotLeaveAnActiveFamily() throws Exception {
        AuthTestAccount a = account();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(() -> refresh(a.refresh()).statusCode(), () -> {
                jdbc.update(
                        """
UPDATE app.users
SET status = 'DISABLED'
WHERE id = ?
""",
                        a.user());

                return 204;
            }));
            assertTrue(Set.of(200, 401).contains(results.getFirst().get()));
            assertEquals(204, results.get(1).get());
        }

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.auth_sessions
                        WHERE user_id = ?
                            AND revoked_at IS NULL
                        """,
                        Integer.class,
                        a.user()));
        assertEquals(401, refresh(a.refresh()).statusCode());
    }

    @Test
    @DisplayName("Шестая ошибка входа ограничивается")
    void invalidLoginIsUniformAndSixthAttemptIsThrottled() throws Exception {
        AuthTestAccount a = account();
        var first = json(login(a.email(), "wrong", a.device()), 401);
        var unknown = json(login(UUID.randomUUID() + "@example.test", "wrong", a.device()), 401);
        assertEquals(first.get("code"), unknown.get("code"));
        assertEquals(first.get("message"), unknown.get("message"));

        for (int i = 1; i < 5; i++) {
            assertEquals(401, login(a.email(), "wrong", a.device()).statusCode());
        }

        var limited = login(a.email(), PASSWORD, a.device());
        assertEquals(429, limited.statusCode());
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test
    @DisplayName("Replay refresh отзывает семью")
    void refreshRotatesInSameFamilyAndReplayCommitsWholeFamilyRevocation() throws Exception {
        AuthTestAccount a = account();
        var other = json(login(a.email(), PASSWORD, UUID.randomUUID()), 200);
        UUID family = jdbc.queryForObject(
                """
        SELECT family_id
        FROM app.auth_sessions
        WHERE user_id = ?
            AND device_id = ?
        """,
                UUID.class,
                a.user(),
                a.device());
        assertEquals(401, refresh(CryptoTokens.random(32)).statusCode());
        var next = json(refresh(a.refresh()), 200);
        String nextAccess = (String) next.get("accessToken"), nextRefresh = (String) next.get("refreshToken");
        assertNotEquals(a.refresh(), nextRefresh);
        assertEquals(
                family,
                jdbc.queryForObject(
                        """
                        SELECT family_id
                        FROM app.auth_sessions
                        WHERE refresh_token_hash = ?
                        """,
                        UUID.class,
                        CryptoTokens.hash(nextRefresh)));
        assertEquals(401, request("GET", "/api/v1/me", null, a.access()).statusCode());
        assertEquals(200, request("GET", "/api/v1/me", null, nextAccess).statusCode());
        assertEquals(401, refresh(a.refresh()).statusCode());
        assertEquals(401, request("GET", "/api/v1/me", null, nextAccess).statusCode());
        assertEquals(401, refresh(nextRefresh).statusCode());
        assertNotNull(
                jdbc.queryForObject(
                        """
                        SELECT revoked_at
                        FROM app.auth_sessions
                        WHERE family_id = ?
                        """,
                        Timestamp.class,
                        family));
        assertEquals(
                200,
                request("GET", "/api/v1/me", null, (String) other.get("accessToken")).statusCode());
    }

    @Test
    @DisplayName("Гонка refresh не оставляет активных ветвей")
    void twoConcurrentRefreshesDoNotLeaveTwoBranchesOrAnActiveFamily() throws Exception {
        AuthTestAccount a = account();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<HttpResponse<String>>>of(() -> refresh(a.refresh()), () -> refresh(a.refresh())));
            assertEquals(
                    Set.of(200, 401),
                    Set.of(results.get(0).get().statusCode(), results.get(1).get().statusCode()));
        }

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.auth_sessions
                        WHERE user_id = ?
                            AND revoked_at IS NULL
                        """,
                        Integer.class,
                        a.user()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.used_refresh_tokens t
                        JOIN app.auth_sessions s USING(family_id)
                        WHERE s.user_id = ?
                        """,
                        Integer.class,
                        a.user()));
    }

    @Test
    @DisplayName("Logout и DISABLED сразу отзывают доступ")
    void logoutAndDisableImmediatelyRejectExistingJwtAndRefresh() throws Exception {
        AuthTestAccount a = account();
        assertEquals(204, request("POST", "/api/v1/auth/logout", null, a.access()).statusCode());
        assertEquals(401, request("GET", "/api/v1/me", null, a.access()).statusCode());
        assertEquals(401, refresh(a.refresh()).statusCode());
        assertEquals(401, request("POST", "/api/v1/auth/logout", null, a.access()).statusCode());
        var first = json(login(a.email(), PASSWORD, UUID.randomUUID()), 200);
        var second = json(login(a.email(), PASSWORD, UUID.randomUUID()), 200);
        jdbc.update(
                """
                UPDATE app.users
                SET status = 'DISABLED'
                WHERE id = ?
                """,
                a.user());

        for (var tokens : List.of(first, second)) {
            assertEquals(
                    401,
                    request("GET", "/api/v1/me", null, (String) tokens.get("accessToken"))
                            .statusCode());
            assertEquals(401, refresh((String) tokens.get("refreshToken")).statusCode());
        }

        assertEquals(401, login(a.email(), PASSWORD, UUID.randomUUID()).statusCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.auth_sessions
                        WHERE user_id = ?
                            AND revoked_at IS NULL
                        """,
                        Integer.class,
                        a.user()));
    }

    @Test
    @DisplayName("Удаление с паролем каскадно очищает аккаунт")
    void passwordConfirmedDeletionCascadesAllDataWithoutTouchingOtherAccounts() throws Exception {
        AuthTestAccount a = account(), b = account();
        var rotated = json(refresh(a.refresh()), 200);
        String access = (String) rotated.get("accessToken");
        asUser(
                a.user(),
                () -> {
                    UUID category = jdbc.queryForList(
                            """
            SELECT id
            FROM app.categories
            WHERE user_id = ?
            """,
                            UUID.class,
                            a.user())
                    .getFirst();
                    jdbc.update(
                            """
                            INSERT INTO app.expenses(user_id, id, category_id, amount_minor, occurred_on)
                            VALUES (?, ?, ?, 1, '2026-10-31')
                            """,
                            a.user(),
                            UUID.randomUUID(),
                            category);
                    jdbc.update(
                            """
                            INSERT INTO app.monthly_budgets(user_id, id, month_start, limit_minor)
                            VALUES (?, ?, '2026-10-01', 1)
                            """,
                            a.user(),
                            UUID.randomUUID());
                    jdbc.update(
                            """
                            INSERT INTO app.category_budgets(user_id, id, category_id, month_start, limit_minor)
                            VALUES (?, ?, ?, '2026-10-01', 1)
                            """,
                            a.user(),
                            UUID.randomUUID(),
                            category);
                    jdbc.update(
                            """
                            INSERT INTO app.sync_operations(user_id, operation_id, request_hash, result_status, result_payload)
                            VALUES (?, ?, repeat('a', 64), 'APPLIED', '{}')
                            """,
                            a.user(),
                            UUID.randomUUID());

                    return null;
                });
        assertEquals(
                403, request("DELETE", "/v1/me", Map.of("password", "wrong"), access).statusCode());
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.users
                        WHERE id = ?
                        """,
                        Integer.class,
                        a.user()));
        assertEquals(
                204,
                request("DELETE", "/v1/me", Map.of("password", PASSWORD), access).statusCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.users
                        WHERE id = ?
                        """,
                        Integer.class,
                        a.user()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.devices
                        WHERE user_id = ?
                        """,
                        Integer.class,
                        a.user()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.auth_sessions
                        WHERE user_id = ?
                        """,
                        Integer.class,
                        a.user()));
        asUser(
                a.user(),
                () -> {
                    for (String table :
                            List.of(
                                    "categories",
                                    "expenses",
                                    "monthly_budgets",
                                    "category_budgets",
                                    "user_sync_state",
                                    "sync_operations",
                                    "sync_changes")) {
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
        assertEquals(200, request("GET", "/api/v1/me", null, b.access()).statusCode());
        assertEquals(401, refresh((String) rotated.get("refreshToken")).statusCode());
        assertEquals(401, request("GET", "/api/v1/me", null, access).statusCode());
        assertEquals(
                1,
                admin.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.registration_invites
                        WHERE code_hash = ?
                            AND used_at IS NOT NULL
                            AND used_by IS NULL
                        """,
                        Integer.class,
                        CryptoTokens.hash(a.invite())));
    }

    @Test
    @DisplayName("Ошибки API не раскрывают секреты")
    void validationAndBearerErrorsAreSafeAndUnknownRoutesStayClosed() throws Exception {
        assertEquals(401, request("GET", "/api/v1/me", null, null).statusCode());
        assertEquals(401, request("GET", "/api/v1/me", null, "broken").statusCode());
        assertEquals(403, request("GET", "/api/v1/unknown", null, null).statusCode());
        assertEquals(
                400,
                request("POST", "/api/v1/auth/login", Map.of("email", "bad"), null).statusCode());
        String code = invite();
        String email = UUID.randomUUID() + "@example.test";

        for (String password : List.of("short", "password123456", "x".repeat(129))) {
            assertEquals(
                    422,
                    request(
                                    "POST",
                                    "/api/v1/auth/register",
                                    registerBody(email, UUID.randomUUID(), code, password),
                                    null)
                            .statusCode());
        }

        var body = new HashMap<>(registerBody(email, UUID.randomUUID(), code, PASSWORD));
        body.put("baseCurrencyCode", "XXX");
        assertEquals(422, request("POST", "/api/v1/auth/register", body, null).statusCode());
        var invalid = HTTP.send(HttpRequest.newBuilder(URI.create(APP.url + "/api/v1/auth/refresh")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{broken")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalid.statusCode());
        assertFalse(invalid.body().contains("Exception"));
        var basic = HTTP.send(HttpRequest.newBuilder(URI.create(APP.url + "/api/v1/me")).header("Authorization", "Basic ignored").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, basic.statusCode());
    }

    @Test
    @DisplayName("CLI приглашений соблюдает TTL и права")
    void inviteCommandDefaultsCustomTtlAndPrivilegesAreEnforced() throws Exception {
        String code = invite();
        assertEquals(16, Base64.getUrlDecoder().decode(code).length);
        assertEquals(
                7,
                admin.queryForObject(
                        """
                        SELECT extract(day FROM(expires_at - created_at))
                        FROM app.registration_invites
                        WHERE code_hash = ?
                        """,
                        Integer.class,
                        CryptoTokens.hash(code)));
        String custom = invite("--ttl", "P2D");
        assertEquals(
                2,
                admin.queryForObject(
                        """
                        SELECT extract(day FROM(expires_at - created_at))
                        FROM app.registration_invites
                        WHERE code_hash = ?
                        """,
                        Integer.class,
                        CryptoTokens.hash(custom)));

        for (String ttl : List.of("P0D", "-P1D", "invalid")) {
            assertThrows(RuntimeException.class, () -> invite("--ttl=" + ttl));
        }

        assertThrows(IllegalArgumentException.class, () -> invite("--unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InviteCommand.run(
                                new String[] {"create-invite"},
                                Map.of(),
                                Clock.systemUTC(),
                                System.out));
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbc.update(
                                """
                                INSERT INTO app.registration_invites(code_hash, expires_at)
                                VALUES (repeat('b', 64), now() + interval '1 day')
                                """));
        assertThrows(
                DataAccessException.class,
                () ->
                        admin.queryForList(
                                """
                                SELECT *
                                FROM app.users
                                """));
    }

    @Test
    @DisplayName("Запуск с неверной ролью запрещён")
    void startupGuardRejectsWrongDatabaseRoles() {
        var migratorJdbc = new JdbcTemplate(APP.database.flyway().getConfiguration().getDataSource());

        for (var pair : List.of(List.of(migratorJdbc, migratorJdbc), List.of(jdbc, jdbc))) {
            new ApplicationContextRunner()
                    .withBean(
                            "runtime",
                            JdbcTemplate.class,
                            () -> pair.get(0),
                            definition -> definition.setPrimary(true))
                    .withBean("migrationJdbc", JdbcTemplate.class, () -> pair.get(1))
                    .withBean("flywayInitializer", String.class, () -> "already migrated")
                    .withBean(DatabaseRoleGuard.class)
                    .run(
                            context ->
                                    Assertions.assertThat(context.getStartupFailure())
                                            .hasRootCauseInstanceOf(IllegalStateException.class));
        }
    }
}
