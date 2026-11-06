package com.financetracker.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.nimbusds.jose.util.JSONObjectUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class MailFixture implements AutoCloseable {
    private final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("ghcr.io/axllent/mailpit:v1.31.3@sha256:ed9b00c609e77e99c79b93f1178255ebc271868920f2c69a8d166bd5634ed10d")).withExposedPorts(1025, 8025).withEnv("MP_DISABLE_VERSION_CHECK", "true").waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public void start() {
        container.start();
    }

    public Map<String, Object> properties() {
        return Map.of("SMTP_HOST", container.getHost(), "SMTP_PORT", container.getMappedPort(1025));
    }

    public String token(String email, String purpose) throws Exception {
        return token(email, purpose, null);
    }

    public String token(String email, String purpose, String previous) throws Exception {
        var result = new CompletableFuture<String>();

        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var poll = scheduler.scheduleAtFixedRate(() -> {
                try {
                    String base = "http://" + container.getHost() + ":" + container.getMappedPort(8025);
                    var messages = JSONObjectUtils.parse(get(base + "/api/v1/messages"));

                    for (Object item : (List<?>) messages.get("messages")) {
                        var entry = (Map<?, ?>) item;

                        if (!entry.get("Subject").toString().contains(purpose)) {
                            continue;
                        }

                        boolean recipient = ((List<?>) entry.get("To")).stream().anyMatch(to -> ((Map<?, ?>) to).get("Address").toString().equalsIgnoreCase(email));

                        if (!recipient) {
                            continue;
                        }

                        var message = JSONObjectUtils.parse(get(base + "/api/v1/message/" + entry.get("ID")));
                        var matcher = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(message.get("Text").toString());

                        if (matcher.find() && !matcher.group(1).equals(previous)) {
                            result.complete(matcher.group(1));
                            return;
                        }
                    }
                } catch (Exception failure) {
                    result.completeExceptionally(
                            new IllegalStateException("Local SMTP capture failed"));
                }
            }, 0, 25, TimeUnit.MILLISECONDS);

            try {
                return result.get(10, TimeUnit.SECONDS);
            } finally {
                poll.cancel(true);
                scheduler.shutdownNow();
            }
        }
    }

    private String get(String url) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Mailpit API unavailable");
        }

        return response.body();
    }

    public void close() {
        container.stop();
    }
}
