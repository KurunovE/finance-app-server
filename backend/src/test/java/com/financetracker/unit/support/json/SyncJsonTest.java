package com.financetracker.unit.support.json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.financetracker.exception.AuthFailure;
import com.financetracker.support.json.SyncJson;
import com.financetracker.support.json.impl.JacksonSyncJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncJsonTest {
    final SyncJson json = new JacksonSyncJson(JsonMapper.builder().build());

    @Test
    @DisplayName("Порядок ключей не меняет идемпотентность")
    void reorderingObjectKeysCannotChangeIdempotencyHashInput() {
        var first = new LinkedHashMap<String, Object>();
        first.put("z", 1);
        first.put("a", 2);
        assertEquals(
                json.write(json.canonical(first)),
                json.write(json.canonical(Map.of("a", 2, "z", 1))));
    }

    @Test
    @DisplayName("Объекты внутри массивов канонизируются")
    void nestedObjectsAreCanonicalizedInsideArrays() {
        var input = Map.of("items", List.of(Map.of("z", 1, "a", 2)));
        assertEquals("{\"items\":[{\"a\":2,\"z\":1}]}", json.write(json.canonical(input)));
    }

    @Test
    @DisplayName("Порядок массива сохраняется")
    void arrayOrderRemainsSignificant() {
        assertNotEquals(
                json.write(json.canonical(List.of(1, 2))),
                json.write(json.canonical(List.of(2, 1))));
    }

    @Test
    @DisplayName("Null отличается от отсутствующего поля")
    void explicitNullAndOmittedFieldRemainDifferent() {
        var input = new HashMap<String, Object>();
        input.put("note", null);
        assertNotEquals(json.write(json.canonical(input)), json.write(json.canonical(Map.of())));
        assertEquals("{\"note\":null}", json.write(json.canonical(input)));
    }

    @Test
    @DisplayName("Int64 сохраняется при сериализации")
    void maximumSignedMoneySurvivesStoredResultRoundTrip() {
        assertEquals(
                Long.MAX_VALUE,
                json.read(json.write(Map.of("amountMinor", Long.MAX_VALUE))).get("amountMinor"));
    }

    @Test
    @DisplayName("Вложенность восемь допустима")
    void depthEightIsAccepted() {
        assertDoesNotThrow(() -> json.canonical(nested(8)));
    }

    @Test
    @DisplayName("Вложенность девять отклоняется")
    void ninthNestedValueIsRejectedBeforeSerialization() {
        var failure = assertThrows(AuthFailure.class, () -> json.canonical(nested(9)));
        assertEquals(400, failure.status());
        assertEquals("PAYLOAD_TOO_DEEP", failure.getMessage());
    }

    @Test
    @DisplayName("Канонизация не меняет входные данные")
    void canonicalizationDoesNotMutateCallerOwnedData() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("z", 1);
        nested.put("a", 2);
        var input = new HashMap<String, Object>();
        input.put("nested", nested);
        json.canonical(input);
        assertEquals(List.of("z", "a"), new ArrayList<>(nested.keySet()));
    }

    private Object nested(int depth) {
        Object value = 1;

        for (int i = 0; i < depth; i++) {
            value = Map.of("nested", value);
        }

        return value;
    }
}
