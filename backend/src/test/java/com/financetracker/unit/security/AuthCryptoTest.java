package com.financetracker.unit.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.financetracker.config.AuthConfiguration;
import com.financetracker.config.properties.AuthSettings;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.security.password.impl.Argon2PasswordPolicy;
import com.financetracker.security.token.CryptoTokens;
import com.financetracker.security.token.JwtTokens;
import com.financetracker.security.token.impl.HmacJwtTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthCryptoTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final UUID EPOCH = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final byte[] secret = new byte[64];

    AuthCryptoTest() {
        new SecureRandom().nextBytes(secret);
    }

    private Path file(byte[] bytes) throws Exception {
        Path dir = Files.createDirectories(Path.of("build/test-secrets"));
        Path file = dir.resolve(UUID.randomUUID() + ".key");

        return Files.write(file, bytes);
    }

    private JwtTokens tokens(String current, Map<String, Path> keys) throws Exception {
        return new HmacJwtTokens(
                AuthSettings.builder()
                        .currentKid(current)
                        .keys(keys)
                        .requestsPerMinute(60)
                        .maxRateLimitKeys(1000)
                        .build(),
                CLOCK);
    }

    @Test
    @DisplayName("Токены сохраняют энтропию и SHA-256")
    void randomTokensHaveExactEntropyEncodingAndStableSha256() {
        String invite = CryptoTokens.random(16), refresh = CryptoTokens.random(32);
        assertEquals(16, Base64.getUrlDecoder().decode(invite).length);
        assertEquals(32, Base64.getUrlDecoder().decode(refresh).length);
        assertFalse(refresh.contains("="));
        assertNotEquals(refresh, CryptoTokens.random(32));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CryptoTokens.hash("abc"));
    }

    @Test
    @DisplayName("Argon2id соответствует параметрам ТЗ")
    void argon2ParametersSaltAndDelegatingFormatMatchSpecification() throws Exception {
        var encoder = new AuthConfiguration().passwordEncoder();
        var policy = new Argon2PasswordPolicy(encoder);
        String password = "correct horse battery staple";
        String first = policy.encodeNew(password), second = policy.encodeNew(password);
        assertTrue(first.startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$"));
        assertNotEquals(first, second);
        String[] parts = first.split("\\$");
        assertEquals(16, Base64.getDecoder().decode(parts[4]).length);
        assertEquals(32, Base64.getDecoder().decode(parts[5]).length);
        assertTrue(policy.matches(password, first));
        assertFalse(policy.matches("wrong", first));
        assertFalse(policy.matches(password, null));
        assertFalse(policy.needsUpgrade(first));
        assertTrue(policy.matches(password, policy.upgrade(password)));
        assertTrue(policy.matches("a".repeat(128), policy.encodeNew("a".repeat(128))));
        assertTrue(policy.matches("😀".repeat(12), policy.encodeNew("😀".repeat(12))));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Слабый пароль отклоняется")
    @ValueSource(strings = {"short", "password123456", "PASSWORD123456", "123456789012"})
    void rejectsShortAndEmbeddedCommonPasswords(String password) throws Exception {
        var policy = new Argon2PasswordPolicy(new AuthConfiguration().passwordEncoder());
        assertEquals(
                422, assertThrows(AuthFailure.class, () -> policy.encodeNew(password)).status());
        assertThrows(AuthFailure.class, () -> policy.encodeNew("x".repeat(129)));
    }

    @Test
    @DisplayName("JWT поддерживает срок и ротацию ключей")
    void jwtClaimsLifetimeAndKeyRotationWork() throws Exception {
        Path old = file(secret), next = file(new SecureRandom().generateSeed(32));
        var oldIssuer = tokens("old", Map.of("old", old));
        var rotated = tokens("next", Map.of("old", old, "next", next));
        UUID user = UUID.randomUUID(), device = UUID.randomUUID(), jti = UUID.randomUUID();
        String original = oldIssuer.issue(user, device, jti, EPOCH);
        assertEquals(
                AccessIdentityDto.builder()
                        .userId(user)
                        .deviceId(device)
                        .jti(jti)
                        .epoch(EPOCH)
                        .build(),
                rotated.verify(original));
        var signed = SignedJWT.parse(rotated.issue(user, device, jti, EPOCH));
        assertEquals("next", signed.getHeader().getKeyID());
        assertEquals(JWSAlgorithm.HS256, signed.getHeader().getAlgorithm());
        assertEquals(
                900,
                Duration.between(
                                signed.getJWTClaimsSet().getIssueTime().toInstant(),
                                signed.getJWTClaimsSet().getExpirationTime().toInstant())
                        .toSeconds());
        assertThrows(AuthFailure.class, () -> oldIssuer.verify(signed.serialize()));
        var expired = new HmacJwtTokens(AuthSettings.builder().currentKid("old").keys(Map.of("old", old)).requestsPerMinute(60).maxRateLimitKeys(100).build(), Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC));        assertThrows(AuthFailure.class, () -> expired.verify(original));
        var other = tokens("old", Map.of("old", next));
        assertThrows(AuthFailure.class, () -> other.verify(original));
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .claim("ep", EPOCH.toString())
                .subject(UUID.randomUUID().toString())
                .claim("did", UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(900)));
    }

    private String sign(JWTClaimsSet claims, JWSAlgorithm algorithm, String kid) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).build(), claims);
        jwt.sign(new MACSigner(secret));

        return jwt.serialize();
    }

    @Test
    @DisplayName("Некорректный JWT отклоняется")
    void rejectsMissingMalformedAndFutureClaimsAndWrongAlgorithms() throws Exception {
        var verifier = tokens("primary", Map.of("primary", file(secret)));

        for (var claims :
                List.of(
                        claims().subject(null).build(),
                        claims().claim("did", "wrong").build(),
                        claims().jwtID(null).build(),
                        claims().issueTime(null).build(),
                        claims().expirationTime(null).build(),
                        claims().issueTime(Date.from(NOW.plusSeconds(1))).build(),
                        claims().expirationTime(Date.from(NOW.plusSeconds(901))).build(),
                        claims().expirationTime(Date.from(NOW.minusSeconds(1))).build())) {
            String token = sign(claims, JWSAlgorithm.HS256, "primary");
            assertThrows(AuthFailure.class, () -> verifier.verify(token));
        }

        assertThrows(
                AuthFailure.class,
                () -> verifier.verify(sign(claims().build(), JWSAlgorithm.HS384, "primary")));
        assertThrows(
                AuthFailure.class,
                () -> verifier.verify(sign(claims().build(), JWSAlgorithm.HS256, "missing")));
        assertThrows(
                AuthFailure.class,
                () -> verifier.verify(sign(claims().build(), JWSAlgorithm.HS256, null)));
        assertThrows(AuthFailure.class, () -> verifier.verify("eyJhbGciOiJub25lIn0.e30."));
        assertThrows(AuthFailure.class, () -> verifier.verify("broken"));
        assertThrows(AuthFailure.class, () -> verifier.verify("a".repeat(8193)));
    }

    @Test
    @DisplayName("Ключ JWT обязателен и достаточно длинный")
    void startupRequiresConfiguredStrongReadableKey() throws Exception {
        Path weak = file(new byte[31]);
        assertThrows(
                IllegalArgumentException.class, () -> tokens("primary", Map.of("primary", weak)));
        assertThrows(
                IOException.class,
                () -> tokens("primary", Map.of("primary", Path.of("build/missing-key"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AuthSettings.builder()
                                .currentKid("missing")
                                .keys(Map.of())
                                .requestsPerMinute(60)
                                .maxRateLimitKeys(100)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AuthSettings.builder()
                                .currentKid("p")
                                .keys(Map.of("p", weak))
                                .requestsPerMinute(0)
                                .maxRateLimitKeys(100)
                                .build());
    }
}
