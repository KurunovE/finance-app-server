package com.financetracker.policy;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SyncPolicy {
    public static final int MAX_BODY_BYTES = 256 * 1024;
    public static final int MAX_PAYLOAD_DEPTH = 8;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 50;
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 200;
    public static final String DEFAULT_PAGE_SIZE = "100";
    public static final String INITIAL_CURSOR = "0";
    public static final int LOOKAHEAD = 1;
    public static final long RECOVERY_LOCK_KEY = 1977041921L;
}
