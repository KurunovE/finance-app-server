package com.financetracker.policy;

import lombok.experimental.UtilityClass;

@UtilityClass
public class MailPolicy {
    public static final int CORE_WORKERS = 1;
    public static final int MAX_WORKERS = 2;
    public static final int QUEUE_CAPACITY = 100;
    public static final int SHUTDOWN_TIMEOUT_SECONDS = 3;
    public static final String THREAD_PREFIX = "account-mail-";
}
