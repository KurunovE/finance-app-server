package com.financetracker.security.password;

public interface PasswordPolicy {
    String encodeNew(String password);

    boolean matches(String password, String stored);

    boolean needsUpgrade(String stored);

    String upgrade(String password);
}
