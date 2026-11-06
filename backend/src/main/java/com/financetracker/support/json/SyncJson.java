package com.financetracker.support.json;

import java.util.Map;

public interface SyncJson {
    String write(Object value);

    Map<String, Object> read(String value);

    Object canonical(Object value);
}
