package com.financetracker.repository.support;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
public class QueryResults {
    public <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.getFirst();
    }
}
