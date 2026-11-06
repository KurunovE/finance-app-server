package com.financetracker.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

public final class PostgresFixture implements AutoCloseable {
    public final String runtimePassword = UUID.randomUUID().toString();
    public final String migratorPassword = UUID.randomUUID().toString();
    public final String invitePassword = UUID.randomUUID().toString();
    public final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18.3").withUsername("finance_bootstrap").withEnv("DB_RUNTIME_PASSWORD", runtimePassword).withEnv("DB_MIGRATOR_PASSWORD", migratorPassword).withEnv("DB_INVITE_PASSWORD", invitePassword).withCopyFileToContainer(MountableFile.forHostPath(Path.of("../infra/postgres/001-roles.sql").toAbsolutePath()), "/docker-entrypoint-initdb.d/001-roles.sql").withCopyFileToContainer(MountableFile.forHostPath(Path.of("../infra/postgres/002-invite-role.sql").toAbsolutePath()), "/docker-entrypoint-initdb.d/002-invite-role.sql");

    public void start() {
        database.start();
    }

    public Map<String, Object> authProperties() {
        try {
            Path directory = Files.createDirectories(Path.of("build/test-secrets"));
            Path file = directory.resolve(UUID.randomUUID() + ".key").toAbsolutePath();
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            Files.write(file, bytes);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

            return Map.of("JWT_KEY_FILE", file.toString());
        } catch (IOException failure) {
            throw new IllegalStateException("Test key setup failed", failure);
        }
    }

    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), "finance_migrator", migratorPassword)
                .defaultSchema("app")
                .schemas("app")
                .createSchemas(false)
                .cleanDisabled(true)
                .load();
    }

    public HikariDataSource runtimePool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.getJdbcUrl());
        config.setUsername("finance_runtime");
        config.setPassword(runtimePassword);
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql(
                """
                SET search_path TO app,
                    pg_catalog
                """);

        return new HikariDataSource(config);
    }

    @Override
    public void close() {
        database.stop();
    }
}
