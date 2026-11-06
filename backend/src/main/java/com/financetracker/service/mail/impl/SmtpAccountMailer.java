package com.financetracker.service.mail.impl;

import java.util.concurrent.Executor;

import com.financetracker.config.BeanNames;
import com.financetracker.config.properties.AccountMailSettings;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.service.mail.AccountMailer;
import com.financetracker.service.mail.AfterCommitMailDispatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public final class SmtpAccountMailer implements AccountMailer {
    private static final String MAIL_REQUIRES_A_TRANSACTION = "Mail requires a transaction";
    private static final String SUBJECT_PREFIX = "Finance Tracker: ";
    private static final String VERIFICATION_PATH = "/verify-email";
    private static final String RESET_PASSWORD_PATH = "/reset-password";
    private static final String TRAILING_SLASH_PATTERN = "/$";
    private static final String BODY_PREFIX = "Open this link in the Finance Tracker client:\n";
    private static final String TOKEN_FRAGMENT_PREFIX = "#token=";
    private static final String BODY_SUFFIX = "\nThis one-time link expires. If you did not request it, ignore this message.";
    private static final String DELIVERY_FAILURE_LOG = "Account mail delivery failed type={}";

    private final JavaMailSender sender;

    @Qualifier(BeanNames.ACCOUNT_MAIL_EXECUTOR)
    private final Executor executor;

    private final AccountMailSettings settings;

    @Override
    public void afterCommit(String recipient, String purpose, String token) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(MAIL_REQUIRES_A_TRANSACTION);
        }

        TransactionSynchronizationManager.registerSynchronization(
                AfterCommitMailDispatch.builder()
                        .executor(executor)
                        .delivery(() -> deliver(recipient, purpose, token))
                        .build());
    }

    private void deliver(String recipient, String purpose, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(recipient);
        message.setSubject(SUBJECT_PREFIX + purpose);
        String path = purpose.equals(AuthPolicy.VERIFY_EMAIL) ? VERIFICATION_PATH : RESET_PASSWORD_PATH;
        String base = settings.publicUrl().toString().replaceAll(TRAILING_SLASH_PATTERN, "");
        message.setText(BODY_PREFIX + base + path + TOKEN_FRAGMENT_PREFIX + token + BODY_SUFFIX);

        try {
            sender.send(message);
        } catch (MailException failed) {
            log.warn(DELIVERY_FAILURE_LOG, failed.getClass().getSimpleName());
        }
    }
}
