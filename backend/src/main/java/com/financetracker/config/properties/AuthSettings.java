package com.financetracker.config.properties;

import java.nio.file.Path;
import java.util.Map;

import com.financetracker.config.ConfigPrefixes;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Builder
@ConfigurationProperties(ConfigPrefixes.AUTH)
public record AuthSettings(
    String currentKid,
    Map<String, Path> keys,
    int requestsPerMinute,
    int maxRateLimitKeys
) {
    private static final String EXPLICIT_AUTH_KEYS_AND_POSITIVE_LIMITER_SETTINGS_REQUIRED = "Explicit auth keys and positive limiter settings required";

    public AuthSettings {
        if (currentKid == null
            || keys == null
            || !keys.containsKey(currentKid)
            || requestsPerMinute < 1
            || maxRateLimitKeys < 1
        ) {
            throw new IllegalArgumentException(EXPLICIT_AUTH_KEYS_AND_POSITIVE_LIMITER_SETTINGS_REQUIRED);
        }

        keys = Map.copyOf(keys);
    }
}
