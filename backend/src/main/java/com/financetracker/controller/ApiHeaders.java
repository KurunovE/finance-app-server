package com.financetracker.controller;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ApiHeaders {
    public static final String RECOVERY_EPOCH = "X-Recovery-Epoch";
    public static final String AUTHORIZATION = "Authorization";
    public static final String RETRY_AFTER = "Retry-After";
    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String NO_STORE = "no-store";
    public static final String JSON_CONTENT_TYPE = "application/json";
}
