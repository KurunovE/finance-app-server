package com.financetracker.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.financetracker.FinanceTrackerApplication;
import com.financetracker.support.MailFixture;
import com.financetracker.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationSmokeTest {
    private static final PostgresFixture DATABASE = new PostgresFixture();
    private static final MailFixture MAIL = new MailFixture();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ConfigurableApplicationContext context;
    private static String baseUrl;

    @BeforeAll
    static void startApplication() {
        MAIL.start();
        DATABASE.start(); // Docker is mandatory; never skip this test when unavailable.
        SpringApplication application = new SpringApplication(FinanceTrackerApplication.class);
        var properties = new HashMap<String, Object>(Map.of("DB_URL", DATABASE.database.getJdbcUrl(), "DB_RUNTIME_USER", "finance_runtime", "DB_RUNTIME_PASSWORD", DATABASE.runtimePassword, "DB_MIGRATOR_USER", "finance_migrator", "DB_MIGRATOR_PASSWORD", DATABASE.migratorPassword, "spring.profiles.active", "test"));
        properties.putAll(DATABASE.authProperties());
        properties.putAll(MAIL.properties());
        application.setDefaultProperties(properties);
        application.addInitializers(
                ctx ->
                        ctx.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "isolated-local-fixture", properties)));
        context = application.run("--server.port=0", "--spring.profiles.active=test");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }

        DATABASE.close();
        MAIL.close();
    }

    @Test
    @DisplayName("Health проверяет БД без раскрытия деталей")
    void healthChecksActualDatabaseWithoutExposingDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
    }

    @Test
    @DisplayName("Неизвестный API закрыт")
    void unknownApiRouteIsDenied() throws Exception {
        HttpResponse<String> response = get("/api/v1/unknown");
        assertEquals(403, response.statusCode());
        assertFalse(response.body().contains("Exception"));
    }

    @Test
    @DisplayName("Окружение Actuator недоступно извне")
    void actuatorEnvironmentIsNotPublic() throws Exception {
        HttpResponse<String> response = get("/actuator/env");
        assertEquals(403, response.statusCode());
        assertFalse(response.body().contains("DB_RUNTIME_PASSWORD"));
    }

    @Test
    @DisplayName("Перезапуск сохраняет схему и health")
    void restartOnMigratedDatabaseKeepsHealthAndSchemaValid() throws Exception {
        context.close();
        startApplication();
        assertEquals(200, get("/actuator/health").statusCode());
        assertEquals(0, DATABASE.flyway().migrate().migrationsExecuted);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
