package com.financetracker.support;

import java.util.Locale;

import lombok.experimental.UtilityClass;

@UtilityClass
public class EmailAddresses {
    public String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
