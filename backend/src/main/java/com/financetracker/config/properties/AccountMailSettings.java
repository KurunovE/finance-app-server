package com.financetracker.config.properties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import com.financetracker.config.ConfigPrefixes;
import com.financetracker.policy.AuthPolicy;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(ConfigPrefixes.ACCOUNT_MAIL)
@Builder
public record AccountMailSettings(String from, URI publicUrl, Duration verificationTtl, Duration resetTtl) {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    private static final String LOCALHOST = "localhost";
    private static final String IPV4_LOOPBACK = "127.0.0.1";
    private static final String IPV6_LOOPBACK = "[::1]";
    private static final String VALID_ACCOUNT_MAIL_URLS_AND_BOUNDED_TOKEN_TTLS_REQUIRED = "Valid account mail URLs and bounded token TTLs required";

    public AccountMailSettings {
        if (from == null
            || from.isBlank()
            || publicUrl == null
            || publicUrl.getHost() == null
            || !(HTTPS.equals(publicUrl.getScheme())
            || HTTP.equals(publicUrl.getScheme()))
            || publicUrl.getUserInfo() != null
            || (HTTP.equals(publicUrl.getScheme())
            && !Set.of(LOCALHOST, IPV4_LOOPBACK, IPV6_LOOPBACK).contains(publicUrl.getHost()))
            || publicUrl.getFragment() != null
            || publicUrl.getQuery() != null
            || verificationTtl == null
            || verificationTtl.isNegative()
            || verificationTtl.isZero()
            || verificationTtl.compareTo(AuthPolicy.MAX_VERIFICATION_TTL) > 0
            || resetTtl == null
            || resetTtl.isNegative()
            || resetTtl.isZero()
            || resetTtl.compareTo(AuthPolicy.MAX_RESET_TTL) > 0
        ) {
            throw new IllegalArgumentException(VALID_ACCOUNT_MAIL_URLS_AND_BOUNDED_TOKEN_TTLS_REQUIRED);
        }
    }
}
