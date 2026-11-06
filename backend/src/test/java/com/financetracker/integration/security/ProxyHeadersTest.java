package com.financetracker.integration.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.financetracker.support.ApplicationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyHeadersTest {
    private HttpResponse<String> get(ApplicationFixture app, String forwarded) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(app.url + "/api/v1/me"))
                                .header("X-Forwarded-For", forwarded)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Local игнорирует поддельный proxy IP")
    void localProfileIgnoresForgedForwardedHeadersAndUsesSocketIp() throws Exception {
        try (var app = new ApplicationFixture()) {
            app.start("--app.auth.requests-per-minute=2");
            assertEquals(401, get(app, "198.51.100.1").statusCode());
            assertEquals(401, get(app, "198.51.100.2").statusCode());
            var limited = get(app, "198.51.100.3");
            assertEquals(429, limited.statusCode());
            assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        }
    }

    @Test
    @DisplayName("Proxy IP принимается только от доверенного адреса")
    void nativeHeadersAreUsedOnlyForExplicitlyTrustedProxy() throws Exception {
        try (var app = new ApplicationFixture()) {
            app.start(
                    "--app.auth.requests-per-minute=2",
                    "--server.forward-headers-strategy=native",
                    "--server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
                    "--server.tomcat.remoteip.trusted-proxies=");
            assertEquals(401, get(app, "198.51.100.1").statusCode());
            assertEquals(401, get(app, "198.51.100.1").statusCode());
            assertEquals(429, get(app, "198.51.100.1").statusCode());
            assertEquals(401, get(app, "198.51.100.2").statusCode());
        }

        try (var app = new ApplicationFixture()) {
            app.start(
                    "--app.auth.requests-per-minute=1",
                    "--server.forward-headers-strategy=native",
                    "--server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1",
                    "--server.tomcat.remoteip.trusted-proxies=");
            assertEquals(401, get(app, "198.51.100.1").statusCode());
            assertEquals(429, get(app, "198.51.100.2").statusCode());
        }
    }
}
