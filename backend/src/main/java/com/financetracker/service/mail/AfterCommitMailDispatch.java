package com.financetracker.service.mail;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;

@Slf4j
@Builder
public final class AfterCommitMailDispatch implements TransactionSynchronization {
    private static final String ACCOUNT_MAIL_QUEUE_FULL_REQUEST_RESEND_LATER = "Account mail queue full; request resend later";

    private final Executor executor;
    private final Runnable delivery;

    @Override
    public void afterCommit() {
        try {
            executor.execute(delivery);
        } catch (RejectedExecutionException ignored) {
            log.warn(ACCOUNT_MAIL_QUEUE_FULL_REQUEST_RESEND_LATER);
        }
    }
}
