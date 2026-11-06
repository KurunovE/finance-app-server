package com.financetracker.support.json.impl;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.policy.SyncPolicy;
import com.financetracker.support.json.SyncJson;
import com.financetracker.support.json.SyncPayloadType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class JacksonSyncJson implements SyncJson {

    private static final String CANNOT_ENCODE_SYNC_DATA = "Cannot encode sync data";
    private static final String CANNOT_DECODE_SYNC_DATA = "Cannot decode sync data";

    private final ObjectMapper mapper;

    @Override
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(CANNOT_ENCODE_SYNC_DATA, failure);
        }
    }

    @Override
    public Map<String, Object> read(String value) {
        try {
            return mapper.readValue(value, new SyncPayloadType());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(CANNOT_DECODE_SYNC_DATA, failure);
        }
    }

    @Override
    public Object canonical(Object value) {
        return canonical(value, 0);
    }

    private Object canonical(Object value, int depth) {
        if (depth > SyncPolicy.MAX_PAYLOAD_DEPTH) {
            throw AuthFailure.builder()
                    .status(HttpStatus.BAD_REQUEST.value())
                    .code(ErrorCodes.PAYLOAD_TOO_DEEP)
                    .build();
        }

        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();

            for (var e : map.entrySet()) {
                sorted.put((String) e.getKey(), canonical(e.getValue(), depth + 1));
            }

            return sorted;
        }

        if (value instanceof List<?> list) {
            return list.stream().map(item -> canonical(item, depth + 1)).toList();
        }

        return value;
    }
}
