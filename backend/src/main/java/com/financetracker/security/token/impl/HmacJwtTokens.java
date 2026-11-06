package com.financetracker.security.token.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

import com.financetracker.config.properties.AuthSettings;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.security.token.JwtTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
public final class HmacJwtTokens implements JwtTokens {
    private static final String JWT_SECRET_MUST_CONTAIN_RANDOM_RAW_BYTES = "JWT secret must contain 32..4096 random raw bytes";
    private static final String HMACSHA256 = "HmacSHA256";
    private static final String DID = "did";
    private static final String EP = "ep";

    private final String currentKid;
    private final Clock clock;
    private final Map<String, NimbusJwtEncoder> encoders = new HashMap<>();
    private final Map<String, NimbusJwtDecoder> decoders = new HashMap<>();

    public HmacJwtTokens(AuthSettings settings, Clock clock) throws IOException {
        this.currentKid = settings.currentKid();
        this.clock = clock;

        for (var entry : settings.keys().entrySet()) {
            byte[] bytes = Files.readAllBytes(entry.getValue());

            if (bytes.length < AuthPolicy.TOKEN_BYTES || bytes.length > AuthPolicy.MAX_KEY_BYTES) {
                throw new IllegalArgumentException(JWT_SECRET_MUST_CONTAIN_RANDOM_RAW_BYTES);
            }

            var key = new SecretKeySpec(bytes, HMACSHA256);
            var jwk = new OctetSequenceKey.Builder(key).keyID(entry.getKey()).algorithm(JWSAlgorithm.HS256).build();
            encoders.put(
                    entry.getKey(), new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk))));
            var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
            var timestamps = new JwtTimestampValidator(Duration.ZERO);
            timestamps.setClock(clock);
            decoder.setJwtValidator(timestamps);
            decoders.put(entry.getKey(), decoder);
        }
    }

    @Override
    public String issue(UUID user, UUID device, UUID jti, UUID epoch) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().subject(user.toString()).claim(DID, device.toString()).id(jti.toString()).claim(EP, epoch.toString()).issuedAt(now).expiresAt(now.plusSeconds(AuthPolicy.ACCESS_TTL_SECONDS)).build();

        return encoders.get(currentKid)
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).keyId(currentKid).build(),
                                claims))
                .getTokenValue();
    }

    @Override
    public AccessIdentityDto verify(String token) {
        try {
            if (token.length() > AuthPolicy.MAX_JWT_LENGTH) {
                throw AuthFailure.unauthorized();
            }

            String kid = SignedJWT.parse(token).getHeader().getKeyID();
            var decoder = decoders.get(kid);

            if (decoder == null) {
                throw AuthFailure.unauthorized();
            }

            var jwt = decoder.decode(token);
            Instant issued = jwt.getIssuedAt();
            Instant expiry = jwt.getExpiresAt();

            if (issued == null
                    || expiry == null
                    || issued.isAfter(clock.instant())
                    || !expiry.isAfter(issued)
                    || expiry.isAfter(issued.plusSeconds(AuthPolicy.ACCESS_TTL_SECONDS))) {
                throw AuthFailure.unauthorized();
            }

            return AccessIdentityDto.builder()
                    .userId(UUID.fromString(jwt.getSubject()))
                    .deviceId(UUID.fromString(jwt.getClaimAsString(DID)))
                    .jti(UUID.fromString(jwt.getId()))
                    .epoch(UUID.fromString(jwt.getClaimAsString(EP)))
                    .build();
        } catch (ParseException
                | JwtException
                | IllegalArgumentException
                | NullPointerException ignored) {
            throw AuthFailure.unauthorized();
        }
    }
}
