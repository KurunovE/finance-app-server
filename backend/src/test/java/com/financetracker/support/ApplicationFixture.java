package com.financetracker.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.financetracker.FinanceTrackerApplication;
import com.financetracker.support.json.SyncJson;
import com.nimbusds.jose.util.JSONObjectUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

public final class ApplicationFixture implements AutoCloseable {
    public final PostgresFixture database = new PostgresFixture();
    public final MailFixture mail = new MailFixture();
    public ConfigurableApplicationContext context;
    public String url;
    private Map<String, Object> fixtureProperties;

    public void start(String... extraArgs) {
        database.start();
        mail.start();
        var app = new SpringApplication(FinanceTrackerApplication.class);
        var properties = fixtureProperties == null ? new HashMap<String, Object>(database.authProperties()) : fixtureProperties;
        fixtureProperties = properties;
        properties.putAll(mail.properties());
        properties.putAll(
                Map.of(
                        "DB_URL",
                        database.database.getJdbcUrl(),
                        "DB_RUNTIME_USER",
                        "finance_runtime",
                        "DB_RUNTIME_PASSWORD",
                        database.runtimePassword,
                        "DB_MIGRATOR_USER",
                        "finance_migrator",
                        "DB_MIGRATOR_PASSWORD",
                        database.migratorPassword,
                        "spring.profiles.active",
                        "test"));
        app.setDefaultProperties(properties);
        app.addInitializers(
                ctx ->
                        ctx.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "isolated-local-fixture", properties)));
        var args = new ArrayList<String>(List.of("--server.port=0", "--spring.profiles.active=test"));
        args.addAll(List.of(extraArgs));
        context = app.run(args.toArray(String[]::new));
        url =
                "http://127.0.0.1:"
                        + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    public void close() {
        if (context != null) {
            context.close();
        }

        database.close();
        mail.close();
    }

    public void verifyEmail(String email) throws Exception {
        String token = mail.token(email, "VERIFY_EMAIL");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url + "/api/v1/auth/email/verify")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")).build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 204) {
            throw new AssertionError("Email verification failed: " + response.statusCode());
        }
    }

    public String bootstrap(String access) throws Exception {
        var http = HttpClient.newHttpClient();
        var info = call(http, "GET", "/api/v1/sync/status", access, null, null, 200);
        String epoch = (String) info.get("epoch");

        if (!Boolean.TRUE.equals(info.get("requiresFullResync"))) {
            return epoch;
        }

        String id = (String) call(http, "POST", "/api/v1/sync/recovery/start", access, epoch, null, 200).get("recoveryId");
        long cursor = 0;

        while (true) {
            var page = call(http, "GET", "/api/v1/sync/recovery/" + id + "/pull?cursor=" + cursor, access, epoch, null, 200);
            cursor = ((Number) page.get("nextCursor")).longValue();

            if (!Boolean.TRUE.equals(page.get("hasMore"))) {
                break;
            }
        }

        call(
                http,
                "POST",
                "/api/v1/sync/recovery/" + id + "/ack",
                access,
                epoch,
                Map.of(
                        "lastCursor",
                        cursor,
                        "localCopyPreserved",
                        true,
                        "pendingOperationsQuarantined",
                        true),
                204);

        return epoch;
    }

    private Map<String, Object> call(
            HttpClient http,
            String method,
            String path,
            String access,
            String epoch,
            Object body,
            int expected)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url + path)).header("Authorization", "Bearer " + access);

        if (epoch != null) {
            builder.header("X-Recovery-Epoch", epoch);
        }

        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(context.getBean(SyncJson.class).write(body))).build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != expected) {
            throw new AssertionError("Bootstrap " + path + ": " + response.statusCode());
        }

        return response.body().isBlank() ? Map.of() : JSONObjectUtils.parse(response.body());
    }
}
