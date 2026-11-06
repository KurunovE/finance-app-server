package com.financetracker.integration.controller.auth;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.financetracker.admin.InviteCommand;
import com.financetracker.security.token.CryptoTokens;
import com.financetracker.support.ApplicationFixture;
import com.financetracker.support.model.SignupTestAccount;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAccountTest {
    private static final ApplicationFixture APP = new ApplicationFixture();
    private static final String PASSWORD = "violet orchard flowing river", NEXT = "another peaceful mountain meadow";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        APP.start("--app.auth.requests-per-minute=10000");
        pool = APP.database.runtimePool();
        jdbc = new JdbcTemplate(pool);
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }

        APP.close();
    }

    private HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(APP.url + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSONObjectUtils.toJSONString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> json(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());

        return response.body().isBlank() ? Map.of() : JSONObjectUtils.parse(response.body());
    }

    private SignupTestAccount signup() throws Exception {
        var bytes = new ByteArrayOutputStream();
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
                new PrintStream(bytes));
        String email = UUID.randomUUID() + "@example.test";
        UUID device = UUID.randomUUID();
        var registered = post("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD, "deviceId", device.toString(), "baseCurrencyCode", "RUB", "inviteCode", bytes.toString().strip()));
        assertEquals(201, registered.statusCode());
        assertTrue(registered.body().isBlank());

        return SignupTestAccount.builder()
                .email(email)
                .device(device)
                .verifyToken(APP.mail.token(email, "VERIFY_EMAIL"))
                .build();
    }

    private Map<String, Object> login(SignupTestAccount user, String password) throws Exception {
        return json(
                post(
                        "/api/v1/auth/login",
                        Map.of(
                                "email",
                                user.email(),
                                "password",
                                password,
                                "deviceId",
                                user.device().toString())),
                200);
    }

    private Map<String, Object> verified(SignupTestAccount user) throws Exception {
        assertEquals(
                204,
                post("/api/v1/auth/email/verify", Map.of("token", user.verifyToken()))
                        .statusCode());

        return login(user, PASSWORD);
    }

    private String resetToken(SignupTestAccount user) throws Exception {
        assertEquals(
                Map.of("status", "ACCEPTED"),
                json(post("/api/v1/auth/password/forgot", Map.of("email", user.email())), 202));

        return APP.mail.token(user.email(), "RESET_PASSWORD");
    }

    private int me(String access) throws Exception {
        return HTTP.send(
                        HttpRequest.newBuilder(URI.create(APP.url + "/api/v1/me"))
                                .header("Authorization", "Bearer " + access)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    @DisplayName("Email подтверждается одноразовым токеном")
    void emailMustBeConfirmedAndTokensAreHashedSingleUseAndPurposeBound() throws Exception {
        var user = signup();
        assertEquals(
                401,
                post(
                                "/api/v1/auth/login",
                                Map.of(
                                        "email",
                                        user.email(),
                                        "password",
                                        PASSWORD,
                                        "deviceId",
                                        user.device().toString()))
                        .statusCode());
        assertEquals(32, Base64.getUrlDecoder().decode(user.verifyToken()).length);
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.account_tokens
                        WHERE token_hash = ?
                            AND purpose = 'VERIFY_EMAIL'
                            AND used_at IS NULL
                        """,
                        Integer.class,
                        CryptoTokens.hash(user.verifyToken())));
        assertEquals(
                24,
                jdbc.queryForObject(
                        """
                        SELECT extract(epoch FROM(expires_at - created_at)) / 3600
                        FROM app.account_tokens
                        WHERE token_hash = ?
                        """,
                        Integer.class,
                        CryptoTokens.hash(user.verifyToken())));
        assertEquals(
                400,
                post(
                                "/api/v1/auth/password/reset",
                                Map.of("token", user.verifyToken(), "password", NEXT))
                        .statusCode());
        var tokens = verified(user);
        assertEquals(200, me((String) tokens.get("accessToken")));
        assertEquals(
                400,
                post("/api/v1/auth/email/verify", Map.of("token", user.verifyToken()))
                        .statusCode());
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.users
                        WHERE email_norm = ?
                            AND email_verified_at IS NOT NULL
                        """,
                        Integer.class,
                        user.email()));
    }

    @Test
    @DisplayName("Запрос письма не раскрывает аккаунт")
    void requestsDoNotEnumerateUsersAndResendHasDatabaseCooldown() throws Exception {
        var user = signup();
        var known = json(post("/api/v1/auth/email/request", Map.of("email", user.email())), 202);
        var absent = json(post("/api/v1/auth/email/request", Map.of("email", UUID.randomUUID() + "@example.test")), 202);
        assertEquals(known, absent);
        assertEquals(
                CryptoTokens.hash(user.verifyToken()),
                jdbc.queryForObject(
                        """
                        SELECT token_hash
                        FROM app.account_tokens t
                        JOIN app.users u ON u.id = t.user_id
                        WHERE u.email_norm = ?
                        """,
                        String.class,
                        user.email()));
        jdbc.update(
                """
                UPDATE app.account_tokens
                SET created_at = created_at - interval '2 minutes'
                WHERE token_hash = ?
                """,
                CryptoTokens.hash(user.verifyToken()));
        assertEquals(
                202,
                post("/api/v1/auth/email/request", Map.of("email", user.email())).statusCode());
        String next = APP.mail.token(user.email(), "VERIFY_EMAIL", user.verifyToken());
        assertNotEquals(user.verifyToken(), next);
        assertEquals(
                400,
                post("/api/v1/auth/email/verify", Map.of("token", user.verifyToken()))
                        .statusCode());
        assertEquals(204, post("/api/v1/auth/email/verify", Map.of("token", next)).statusCode());
        var forgot = json(post("/api/v1/auth/password/forgot", Map.of("email", user.email())), 202);
        assertEquals(
                forgot,
                json(
                        post(
                                "/api/v1/auth/password/forgot",
                                Map.of("email", UUID.randomUUID() + "@example.test")),
                        202));
        jdbc.update(
                """
                UPDATE app.users
                SET status = 'DISABLED'
                WHERE email_norm = ?
                """,
                user.email());
        assertEquals(
                forgot,
                json(post("/api/v1/auth/password/forgot", Map.of("email", user.email())), 202));
    }

    @Test
    @DisplayName(
            """
            Reset о т з ы в а е т в с е с в о и у с т р о й с т в а
            """)
    void resetRevokesEveryDeviceAndRefreshButDoesNotAffectAnotherAccount() throws Exception {
        var user = signup();
        var first = verified(user);
        var stranger = signup();
        var other = verified(stranger);
        var second = json(post("/api/v1/auth/login", Map.of("email", user.email(), "password", PASSWORD, "deviceId", UUID.randomUUID().toString())), 200);
        String token = resetToken(user);
        assertEquals(
                15,
                jdbc.queryForObject(
                        """
                        SELECT extract(epoch FROM(expires_at - created_at)) / 60
                        FROM app.account_tokens
                        WHERE token_hash = ?
                        """,
                        Integer.class,
                        CryptoTokens.hash(token)));
        assertEquals(400, post("/api/v1/auth/email/verify", Map.of("token", token)).statusCode());
        assertEquals(
                422,
                post(
                                "/api/v1/auth/password/reset",
                                Map.of("token", token, "password", "password123456"))
                        .statusCode());
        assertEquals(
                204,
                post("/api/v1/auth/password/reset", Map.of("token", token, "password", NEXT))
                        .statusCode());

        for (var session : List.of(first, second)) {
            assertEquals(401, me((String) session.get("accessToken")));
            assertEquals(
                    401,
                    post(
                                    "/api/v1/auth/refresh",
                                    Map.of("refreshToken", session.get("refreshToken")))
                            .statusCode());
        }

        assertEquals(
                400,
                post("/api/v1/auth/password/reset", Map.of("token", token, "password", PASSWORD))
                        .statusCode());
        assertEquals(
                401,
                post(
                                "/api/v1/auth/login",
                                Map.of(
                                        "email",
                                        user.email(),
                                        "password",
                                        PASSWORD,
                                        "deviceId",
                                        user.device().toString()))
                        .statusCode());
        assertEquals(200, me((String) login(user, NEXT).get("accessToken")));
        assertEquals(200, me((String) other.get("accessToken")));
    }

    @Test
    @DisplayName("Неверный токен не меняет аккаунт")
    void expiredAndUnknownTokensNeverChangePasswordOrVerification() throws Exception {
        var user = signup();
        jdbc.update(
                """
                UPDATE app.account_tokens
                SET created_at = now() - interval '2 days',
                    expires_at = now() - interval '1 day'
                WHERE token_hash = ?
                """,
                CryptoTokens.hash(user.verifyToken()));
        assertEquals(
                400,
                post("/api/v1/auth/email/verify", Map.of("token", user.verifyToken()))
                        .statusCode());

        for (String token : List.of("invalid", CryptoTokens.random(32))) {
            assertEquals(
                    400,
                    post("/api/v1/auth/password/reset", Map.of("token", token, "password", NEXT))
                            .statusCode());
        }

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.users
                        WHERE email_norm = ?
                            AND email_verified_at IS NOT NULL
                        """,
                        Integer.class,
                        user.email()));
        assertEquals(
                202,
                post("/api/v1/auth/email/request", Map.of("email", user.email())).statusCode());
        String fresh = APP.mail.token(user.email(), "VERIFY_EMAIL", user.verifyToken());
        assertEquals(204, post("/api/v1/auth/email/verify", Map.of("token", fresh)).statusCode());
        String reset = resetToken(user);
        jdbc.update(
                """
                UPDATE app.account_tokens
                SET created_at = now() - interval '2 hours',
                    expires_at = now() - interval '1 hour'
                WHERE token_hash = ?
                """,
                CryptoTokens.hash(reset));
        assertEquals(
                400,
                post("/api/v1/auth/password/reset", Map.of("token", reset, "password", NEXT))
                        .statusCode());
        assertEquals(200, me((String) login(user, PASSWORD).get("accessToken")));
    }

    @Test
    @DisplayName("Параллельный reset использует токен один раз")
    void concurrentResetConsumesTokenOnceAndCannotRestoreOldSession() throws Exception {
        var user = signup();
        var old = verified(user);
        String token = resetToken(user);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(() -> post("/api/v1/auth/password/reset", Map.of("token", token, "password", NEXT)).statusCode(), () -> post("/api/v1/auth/password/reset", Map.of("token", token, "password", NEXT)).statusCode()));
            assertEquals(Set.of(204, 400), Set.of(results.get(0).get(), results.get(1).get()));
        }

        assertEquals(401, me((String) old.get("accessToken")));
        assertEquals(200, me((String) login(user, NEXT).get("accessToken")));
    }
}
