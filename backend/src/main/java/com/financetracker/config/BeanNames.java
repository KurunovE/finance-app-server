package com.financetracker.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BeanNames {
    public static final String WRITE_TRANSACTION = "writeTransaction";
    public static final String SYNC_READ_TRANSACTION = "syncReadTransaction";
    public static final String MIGRATION_JDBC = "migrationJdbc";
    public static final String MIGRATION_TRANSACTION = "migrationTransaction";
    public static final String MIGRATION_RECOVERY_REPOSITORY = "migrationRecoveryRepository";
    public static final String ACCOUNT_MAIL_EXECUTOR = "accountMailExecutor";
    public static final String FLYWAY_INITIALIZER = "flywayInitializer";
}
