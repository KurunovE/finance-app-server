package com.financetracker.unit.service.mail;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

import com.financetracker.config.properties.AccountMailSettings;
import com.financetracker.service.mail.impl.SmtpAccountMailer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountMailTest {
    private AccountMailSettings settings(String url, Duration verify, Duration reset) {
        return AccountMailSettings.builder()
                .from("no-reply@example.test")
                .publicUrl(URI.create(url))
                .verificationTtl(verify)
                .resetTtl(reset)
                .build();
    }

    @Test
    @DisplayName("Ссылки и TTL ограничены")
    void linksMustUseHttpsOrLoopbackAndTtlsAreBounded() {
        for (String url :
                new String[] {
                    "http://example.test",
                    "//example.test/client",
                    "https://example.test/#injected",
                    "https://example.test/?token=x",
                    "https://user:secret@example.test",
                    "file:/tmp/mail"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> settings(url, Duration.ofHours(24), Duration.ofMinutes(15)));
        }

        assertDoesNotThrow(
                () ->
                        settings(
                                "https://example.test/client",
                                Duration.ofDays(7),
                                Duration.ofHours(1)));
        assertDoesNotThrow(
                () ->
                        settings(
                                "http://127.0.0.1:8080",
                                Duration.ofHours(24),
                                Duration.ofMinutes(15)));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("https://example.test", Duration.ZERO, Duration.ofMinutes(15)));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("https://example.test", Duration.ofDays(8), Duration.ofMinutes(15)));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("https://example.test", Duration.ofDays(1), Duration.ofHours(2)));
    }

    @Test
    @DisplayName("Письмо ждёт commit и безопасно переживает сбой")
    void mailWaitsForCommitAndTransportOrQueueFailureDoesNotExposeToken() {
        var sender = Mockito.mock(JavaMailSender.class);
        Mockito.doThrow(new MailSendException("private transport details"))
                .when(sender)
                .send(ArgumentMatchers.any(SimpleMailMessage.class));
        var settings = settings("https://example.test", Duration.ofDays(1), Duration.ofMinutes(15));
        var mail = new SmtpAccountMailer(sender, Runnable::run, settings);
        assertThrows(
                IllegalStateException.class,
                () -> mail.afterCommit("user@example.test", "VERIFY_EMAIL", "private"));
        TransactionSynchronizationManager.initSynchronization();

        try {
            mail.afterCommit("user@example.test", "VERIFY_EMAIL", "private");
            Mockito.verifyNoInteractions(sender);
            assertDoesNotThrow(
                    () ->
                            TransactionSynchronizationManager.getSynchronizations()
                                    .forEach(sync -> sync.afterCommit()));
            Mockito.verify(sender, Mockito.times(1))
                    .send(ArgumentMatchers.any(SimpleMailMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        var busy = new SmtpAccountMailer(sender, task -> {
            throw new RejectedExecutionException();
        }, settings);
        TransactionSynchronizationManager.initSynchronization();

        try {
            busy.afterCommit("user@example.test", "RESET_PASSWORD", "private");
            assertDoesNotThrow(
                    () ->
                            TransactionSynchronizationManager.getSynchronizations()
                                    .forEach(sync -> sync.afterCommit()));
            Mockito.verify(sender, Mockito.times(1))
                    .send(ArgumentMatchers.any(SimpleMailMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
