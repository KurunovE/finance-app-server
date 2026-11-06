package com.financetracker.admin;

import java.io.PrintStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.financetracker.policy.AuthPolicy;
import com.financetracker.security.token.CryptoTokens;

public final class InviteCommand {

    private static final String TTL_ARGUMENT = "--ttl";
    private static final String USAGE_CREATE_INVITE_TTL_P7D = "Usage: create-invite [--ttl P7D]";
    private static final String TTL_MUST_BE_POSITIVE = "TTL must be positive";
    private static final String DATABASE_URL_VARIABLE = "INVITE_DB_URL";
    private static final String DATABASE_USER_VARIABLE = "INVITE_DB_USER";
    private static final String DATABASE_PASSWORD_VARIABLE = "INVITE_DB_PASSWORD";
    private static final String INVITE_ADMINISTRATOR_ENVIRONMENT_REQUIRED = "Invite administrator environment required";
    private static final String TTL_EQUALS_PREFIX = "--ttl=";

    private InviteCommand() {}

    public static void run(
            String[] args, Map<String, String> environment, Clock clock, PrintStream output)
            throws SQLException {
        Duration ttl = AuthPolicy.DEFAULT_INVITE_TTL;

        if (args.length == 2 && args[1].startsWith(TTL_EQUALS_PREFIX)) {
            ttl = Duration.parse(args[1].substring(TTL_EQUALS_PREFIX.length()));
        } else if (args.length == 3 && args[1].equals(TTL_ARGUMENT)) {
            ttl = Duration.parse(args[2]);
        } else if (args.length != 1) {
            throw new IllegalArgumentException(USAGE_CREATE_INVITE_TTL_P7D);
        }

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(TTL_MUST_BE_POSITIVE);
        }

        var now = clock.instant();
        var expiry = now.plus(ttl);
        String code = CryptoTokens.random(AuthPolicy.INVITE_BYTES);

        String script = """
            INSERT INTO app.registration_invites(code_hash, created_at, expires_at)
            VALUES (?, ?, ?)
            """;

        try (var connection = DriverManager.getConnection(required(environment, DATABASE_URL_VARIABLE), required(environment, DATABASE_USER_VARIABLE), required(environment, DATABASE_PASSWORD_VARIABLE))) {
            connection.setAutoCommit(false);

            try (var insert = connection.prepareStatement(script)) {
                insert.setString(1, CryptoTokens.hash(code));
                insert.setTimestamp(2, Timestamp.from(now));
                insert.setTimestamp(3, Timestamp.from(expiry));
                insert.executeUpdate();
                connection.commit();
            }
        }

        output.println(code);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(INVITE_ADMINISTRATOR_ENVIRONMENT_REQUIRED);
        }

        return value;
    }
}
