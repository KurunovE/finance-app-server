package com.financetracker.config.guard;

import com.financetracker.config.BeanNames;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

@Component
@DependsOn(BeanNames.FLYWAY_INITIALIZER)
@RequiredArgsConstructor
public final class DatabaseRoleGuard implements InitializingBean {

    private static final String RESTRICTED_RUNTIME_DATABASE_ROLE_REQUIRED = "Restricted runtime database role required";
    private static final String SEPARATE_MIGRATOR_DATABASE_ROLE_REQUIRED = "Separate migrator database role required";

    private final JdbcOperations runtime;

    @Qualifier(BeanNames.MIGRATION_JDBC)
    private final JdbcOperations migrationJdbc;

    @Override
    public void afterPropertiesSet() {
        Boolean restricted = runtime.queryForObject(
                """
            SELECT current_user = 'finance_runtime'
                AND NOT (
                    rolsuper
                    OR rolbypassrls
                    OR rolcreatedb
                    OR rolcreaterole
                    OR rolreplication
                )
                AND NOT pg_has_role(current_user, 'finance_migrator', 'MEMBER')
                AND NOT pg_has_role(current_user, 'finance_invite_admin', 'MEMBER')
                AND NOT has_schema_privilege('app', 'CREATE')
                AND NOT has_database_privilege(current_database(), 'CREATE')
                AND NOT has_database_privilege(current_database(), 'TEMP')
                AND NOT EXISTS (
                    SELECT 1
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'app'
                        AND c.relowner = (
                            SELECT oid
                            FROM pg_roles
                            WHERE rolname = current_user
                        )
                )
            FROM pg_roles
            WHERE rolname = current_user
            """,
                Boolean.class);

        if (!Boolean.TRUE.equals(restricted)) {
            throw new IllegalStateException(RESTRICTED_RUNTIME_DATABASE_ROLE_REQUIRED);
        }

        Boolean migrator = migrationJdbc.queryForObject(
                """
            SELECT current_user = 'finance_migrator'
                AND NOT (rolsuper OR rolbypassrls OR rolcreaterole)
            FROM pg_roles
            WHERE rolname = current_user
            """,
                Boolean.class);

        if (!Boolean.TRUE.equals(migrator)) {
            throw new IllegalStateException(SEPARATE_MIGRATOR_DATABASE_ROLE_REQUIRED);
        }
    }
}
