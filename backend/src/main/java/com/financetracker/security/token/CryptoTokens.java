package com.financetracker.security.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class CryptoTokens {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String JDK_SHA_UNAVAILABLE = "JDK SHA-256 unavailable";

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoTokens() {}

    public static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance(HASH_ALGORITHM)
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(JDK_SHA_UNAVAILABLE, impossible);
        }
    }
}
