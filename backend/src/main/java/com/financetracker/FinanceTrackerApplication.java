package com.financetracker;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;

import com.financetracker.admin.InviteCommand;
import com.financetracker.admin.RecoveryCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class FinanceTrackerApplication {
    private static final String RECOVERY = "recovery-";
    private static final String RECOVERY_COMMAND_FAILED_VERIFY_EPOCH_RECONCILIATION_AND_ADMINISTRATOR_CREDENTIALS = "Recovery command failed; verify epoch, reconciliation and administrator credentials.";
    private static final String CREATE_INVITE = "create-invite";
    private static final String INVITE_CREATION_FAILED_VERIFY_ARGUMENTS_ADMINISTRATOR_CREDENTIALS_AND_DATABASE = "Invite creation failed; verify arguments, administrator credentials and database readiness.";

    public static void main(String[] args) {
        if (args.length > 0 && args[0].startsWith(RECOVERY)) {
            try {
                RecoveryCommand.run(args, System.getenv(), System.out);
            } catch (SQLException | IllegalArgumentException failure) {
                log.error(
                        "{} type={}",
                        RECOVERY_COMMAND_FAILED_VERIFY_EPOCH_RECONCILIATION_AND_ADMINISTRATOR_CREDENTIALS,
                        failure.getClass().getSimpleName());
                System.exit(2);
            }

            return;
        }

        if (args.length > 0 && args[0].equals(CREATE_INVITE)) {
            try {
                InviteCommand.run(args, System.getenv(), Clock.systemUTC(), System.out);
            } catch (SQLException | IllegalArgumentException | DateTimeException failure) {
                log.error(
                        "{} type={}",
                        INVITE_CREATION_FAILED_VERIFY_ARGUMENTS_ADMINISTRATOR_CREDENTIALS_AND_DATABASE,
                        failure.getClass().getSimpleName());
                System.exit(2);
            }

            return;
        }

        SpringApplication.run(FinanceTrackerApplication.class, args);
    }
}
