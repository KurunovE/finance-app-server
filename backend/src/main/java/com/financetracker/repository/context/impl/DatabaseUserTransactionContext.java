package com.financetracker.repository.context.impl;

import java.util.Objects;
import java.util.UUID;

import com.financetracker.repository.context.UserTransactionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public final class DatabaseUserTransactionContext implements UserTransactionContext {

    private static final String VERIFIED_USER_IS_REQUIRED = "Verified user is required";
    private static final String USER_CONTEXT_REQUIRES_A_TRANSACTION_ON_THIS_DATA_SOURCE = "User context requires a transaction on this data source";
    private static final String USER_CONTEXT_IS_ALREADY_SET_FOR_THIS_TRANSACTION = "User context is already set for this transaction";

    private final JdbcOperations jdbc;
    private final javax.sql.DataSource dataSource;

    @Override
    public void setUser(UUID verifiedUserId) {
        Objects.requireNonNull(verifiedUserId, VERIFIED_USER_IS_REQUIRED);

        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(
                        Objects.requireNonNull(dataSource))) {
            throw new IllegalStateException(
                    USER_CONTEXT_REQUIRES_A_TRANSACTION_ON_THIS_DATA_SOURCE);
        }

        String current = jdbc.queryForObject(
                """
        SELECT NULLIF(current_setting('app.current_user_id', true), '')
        """,
                String.class);

        if (current != null) {
            throw new IllegalStateException(USER_CONTEXT_IS_ALREADY_SET_FOR_THIS_TRANSACTION);
        }

        jdbc.queryForObject(
                """
                SELECT set_config('app.current_user_id', ?, true)
                """,
                String.class,
                verifiedUserId.toString());
    }
}
