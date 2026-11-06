package com.financetracker.admin;

import java.io.PrintStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public final class RecoveryCommand {

    private static final String RECOVERY_ACTIVATE = "recovery-activate";
    private static final String EPOCH = "--epoch";
    private static final String RECONCILED = "--reconciled";
    private static final String ACTIVATION_REQUIRES_EPOCH_UUID_RECONCILED = "Activation requires --epoch UUID --reconciled";
    private static final String RECOVERY_PREPARE = "recovery-prepare";
    private static final String RECOVERY_STATUS = "recovery-status";
    private static final String UNKNOWN_RECOVERY_COMMAND = "Unknown recovery command";
    private static final String DATABASE_URL_VARIABLE = "RECOVERY_DB_URL";
    private static final String DATABASE_USER_VARIABLE = "RECOVERY_DB_USER";
    private static final String DATABASE_PASSWORD_VARIABLE = "RECOVERY_DB_PASSWORD";
    private static final String RECOVERY_STATE_ABSENT_RUN_RECOVERY_PREPARE = "Recovery state absent; run recovery-prepare";
    private static final String RESULT_EPOCH_PREFIX = "{\"epoch\":\"";
    private static final String RESULT_PHASE_SEPARATOR = "\",\"phase\":\"";
    private static final String RESULT_SUFFIX = "\"}";
    private static final String RECOVERY_ADMINISTRATOR_CREDENTIALS_REQUIRED = "Recovery administrator credentials required";

    private RecoveryCommand() {}

    public static void run(String[] args, Map<String, String> environment, PrintStream output)
            throws SQLException {
        String command = args[0];
        UUID expected = null;

        if (command.equals(RECOVERY_ACTIVATE)) {
            if (args.length != 4 || !args[1].equals(EPOCH) || !args[3].equals(RECONCILED)) {
                throw new IllegalArgumentException(ACTIVATION_REQUIRES_EPOCH_UUID_RECONCILED);
            }

            expected = UUID.fromString(args[2]);
        } else if (args.length != 1
                || !(command.equals(RECOVERY_PREPARE) || command.equals(RECOVERY_STATUS))) {
            throw new IllegalArgumentException(UNKNOWN_RECOVERY_COMMAND);
        }

        try (var connection = DriverManager.getConnection(required(environment, DATABASE_URL_VARIABLE), required(environment, DATABASE_USER_VARIABLE), required(environment, DATABASE_PASSWORD_VARIABLE))) {
            connection.setAutoCommit(false);

            if (command.equals(RECOVERY_PREPARE)) {
                try (var query = connection.prepareStatement(
                        """
                    SELECT app.begin_recovery()
                    """)) {
                    query.execute();
                }
            } else if (command.equals(RECOVERY_ACTIVATE)) {
                try (var query = connection.prepareStatement(
                        """
                    SELECT app.activate_recovery(?)
                    """)) {
                    query.setObject(1, expected);
                    query.execute();
                }
            }

            UUID epoch;
            String phase;

            try (var query = connection.prepareStatement(
                    """
        SELECT epoch, phase
        FROM app.recovery_state
        WHERE singleton
        """);
                    var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException(RECOVERY_STATE_ABSENT_RUN_RECOVERY_PREPARE);
                }

                epoch = rows.getObject(1, UUID.class);
                phase = rows.getString(2);
            }

            connection.commit();
            output.println(
                    RESULT_EPOCH_PREFIX + epoch + RESULT_PHASE_SEPARATOR + phase + RESULT_SUFFIX);
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(RECOVERY_ADMINISTRATOR_CREDENTIALS_REQUIRED);
        }

        return value;
    }
}
