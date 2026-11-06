package com.financetracker.security.impl;

import java.time.Instant;
import java.util.ArrayDeque;

import lombok.Builder;
import lombok.Builder.Default;

@Builder
public final class RateLimitBucket {
    @Default final ArrayDeque<Instant> events = new ArrayDeque<>();
    volatile Instant touched;
}
