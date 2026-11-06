package com.financetracker.unit.service.finance;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.financetracker.exception.DomainRejection;
import com.financetracker.model.enums.Action;
import com.financetracker.model.enums.EntityType;
import com.financetracker.service.finance.FinanceRules;
import com.financetracker.service.finance.impl.DefaultFinanceRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceRulesTest {
    private final FinanceRules rules = new DefaultFinanceRules();

    static Stream<Arguments> validMoney() {
        return Stream.of(
                Arguments.of(1, 1L),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(BigInteger.TWO, 2L));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Положительная целая сумма принимается")
    @MethodSource("validMoney")
    void acceptsExactPositiveIntegerMoney(Object input, long expected) {
        assertEquals(expected, rules.positiveMoney(input));
    }

    static Stream<Arguments> invalidMoney() {
        return Stream.of(
                Arguments.of(0, "INVALID_AMOUNT"),
                Arguments.of(-1L, "INVALID_AMOUNT"),
                Arguments.of(1.5, "INVALID_INTEGER"),
                Arguments.of("12", "INVALID_INTEGER"),
                Arguments.of(true, "INVALID_INTEGER"),
                Arguments.of(null, "INVALID_INTEGER"),
                Arguments.of(BigInteger.ONE.shiftLeft(63), "INVALID_INTEGER"));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Неверная сумма не преобразуется")
    @MethodSource("invalidMoney")
    void rejectsMoneyWithoutCoercionOrOverflow(Object input, String code) {
        assertEquals(
                code,
                assertThrows(DomainRejection.class, () -> rules.positiveMoney(input)).getCode());
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Допустимая календарная дата сохраняется")
    @ValueSource(strings = {"2024-02-29", "0001-01-01", "9999-12-31"})
    void preservesValidCalendarDatesIncludingBounds(String date) {
        assertEquals(
                date,
                rules.normalize(EntityType.EXPENSE, Action.UPDATE, Map.of("occurredOn", date))
                        .get("occurredOn"));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Некорректная дата расхода отклоняется")
    @ValueSource(
            strings = {
                "2023-02-29",
                "2024-02-30",
                "2026-1-01",
                "0000-01-01",
                "2026-13-01",
                "2026-01-01T00:00:00Z"
            })
    void rejectsInvalidOrNonDateExpenseDates(String date) {
        assertCode("INVALID_DATE", EntityType.EXPENSE, Action.UPDATE, Map.of("occurredOn", date));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Бюджет начинается первого числа")
    @EnumSource(
            value = EntityType.class,
            names = {"MONTHLY_BUDGET", "CATEGORY_BUDGET"})
    void budgetsRequireFirstDayOfMonth(EntityType type) {
        assertCode("INVALID_DATE", type, Action.UPDATE, Map.of("monthStart", "2026-10-02"));
    }

    @Test
    @DisplayName("Дата должна быть строкой")
    void nonStringDateIsRejected() {
        assertCode(
                "INVALID_DATE", EntityType.EXPENSE, Action.UPDATE, Map.of("occurredOn", 20261031));
    }

    @Test
    @DisplayName("Эквивалентные названия совпадают")
    void nfkcAndCaseNormalizationMakeEquivalentCategoryNamesEqual() {
        assertEquals("food", rules.normalizedName(" ＦOOD "));
        assertEquals("é", rules.normalizedName("e\u0301"));
    }

    @Test
    @DisplayName("Категория получает нормализованное имя и defaults")
    void categoryCreateTrimsNameAndAppliesOnlyCategoryDefaults() {
        assertEquals(
                Map.of("name", "Food", "archived", false, "sortOrder", 0),
                rules.normalize(EntityType.CATEGORY, Action.CREATE, Map.of("name", " Ｆood ")));
    }

    @Test
    @DisplayName("Длина имени проверяется после NFKC")
    void categoryLengthIsMeasuredAfterUnicodeNormalization() {
        assertEquals(
                "é".repeat(80),
                rules.normalize(
                                EntityType.CATEGORY,
                                Action.CREATE,
                                Map.of("name", "e\u0301".repeat(80)))
                        .get("name"));
    }

    @Test
    @DisplayName("Длина имени учитывает Unicode-символы")
    void categoryLimitCountsCodePointsRatherThanUtf16Units() {
        assertEquals(
                "😀".repeat(80),
                rules.normalize(EntityType.CATEGORY, Action.CREATE, Map.of("name", "😀".repeat(80)))
                        .get("name"));
        assertCode(
                "INVALID_TEXT",
                EntityType.CATEGORY,
                Action.CREATE,
                Map.of("name", "😀".repeat(81)));
    }

    @Test
    @DisplayName("Пустое название категории отклоняется")
    void blankCategoryNameIsRejected() {
        assertCode("INVALID_TEXT", EntityType.CATEGORY, Action.CREATE, Map.of("name", " "));
    }

    @Test
    @DisplayName("Число не становится названием")
    void categoryNameCannotBeCoercedFromNumber() {
        assertCode("INVALID_NAME", EntityType.CATEGORY, Action.UPDATE, Map.of("name", 12));
    }

    @Test
    @DisplayName("Заметка предельной длины сохраняется")
    void noteAtLimitIsTrimmedAndPreserved() {
        assertEquals(
                "x".repeat(500),
                rules.normalize(
                                EntityType.EXPENSE,
                                Action.UPDATE,
                                Map.of("note", " " + "x".repeat(500) + " "))
                        .get("note"));
    }

    @Test
    @DisplayName("Длинная заметка отклоняется")
    void noteBeyondLimitIsRejected() {
        assertCode(
                "INVALID_TEXT", EntityType.EXPENSE, Action.UPDATE, Map.of("note", "x".repeat(501)));
    }

    @Test
    @DisplayName("Null очищает заметку")
    void nullNoteExplicitlyClearsExistingNote() {
        var data = new HashMap<String, Object>();
        data.put("note", null);
        var result = rules.normalize(EntityType.EXPENSE, Action.UPDATE, data);
        assertTrue(result.containsKey("note"));
        assertNull(result.get("note"));
    }

    @Test
    @DisplayName("Пробельная заметка очищается")
    void blankNoteClearsExistingNote() {
        assertNull(
                rules.normalize(EntityType.EXPENSE, Action.UPDATE, Map.of("note", " "))
                        .get("note"));
    }

    @Test
    @DisplayName("Отсутствующая заметка не подменяется")
    void omittedNoteOnUpdateRemainsOmitted() {
        assertFalse(
                rules.normalize(EntityType.EXPENSE, Action.UPDATE, Map.of("amountMinor", 1))
                        .containsKey("note"));
    }

    @Test
    @DisplayName("Новый расход получает пустую заметку")
    void expenseCreateDefaultsNoteToNull() {
        var result = rules.normalize(EntityType.EXPENSE, Action.CREATE, Map.of("categoryId", UUID.randomUUID().toString(), "amountMinor", 1, "occurredOn", "2026-10-31"));       assertTrue(result.containsKey("note"));
        assertNull(result.get("note"));
    }

    @Test
    @DisplayName("Владелец не подменяется через payload")
    void unknownFieldCannotInjectUserOwnership() {
        assertCode(
                "INVALID_PAYLOAD",
                EntityType.CATEGORY,
                Action.UPDATE,
                Map.of("userId", UUID.randomUUID().toString()));
    }

    static Stream<Arguments> missingFields() {
        return Stream.of(
                Arguments.of(EntityType.CATEGORY, Map.of("archived", true)),
                Arguments.of(EntityType.EXPENSE, Map.of("amountMinor", 1)),
                Arguments.of(EntityType.MONTHLY_BUDGET, Map.of("monthStart", "2026-10-01")));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Создание требует обязательные поля")
    @MethodSource("missingFields")
    void createRequiresItsOwnMandatoryFields(EntityType type, Map<String, Object> data) {
        assertCode("INVALID_PAYLOAD", type, Action.CREATE, data);
    }

    @Test
    @DisplayName("Неполный новый расход отклоняется")
    void partialExpenseCreateCannotOmitCategoryAmountAndDate() {
        assertCode("INVALID_PAYLOAD", EntityType.EXPENSE, Action.CREATE, Map.of("note", "only"));
    }

    @Test
    @DisplayName("Бюджет категории требует её идентификатор")
    void categoryBudgetCreateRequiresCategoryInAdditionToMonthAndLimit() {
        assertCode(
                "INVALID_PAYLOAD",
                EntityType.CATEGORY_BUDGET,
                Action.CREATE,
                Map.of("monthStart", "2026-10-01", "limitMinor", 1));
    }

    @Test
    @DisplayName("Пустое обновление отклоняется")
    void updateCannotBeEmpty() {
        assertCode("INVALID_PAYLOAD", EntityType.EXPENSE, Action.UPDATE, Map.of());
    }

    @Test
    @DisplayName("Null-обновление отклоняется")
    void updateCannotBeNull() {
        assertCode("INVALID_PAYLOAD", EntityType.EXPENSE, Action.UPDATE, null);
    }

    @Test
    @DisplayName("Удаление допускает пустой payload")
    void deleteAcceptsNoPayload() {
        assertTrue(rules.normalize(EntityType.EXPENSE, Action.DELETE, null).isEmpty());
        assertTrue(rules.normalize(EntityType.EXPENSE, Action.DELETE, Map.of()).isEmpty());
    }

    @Test
    @DisplayName("Удаление не допускает скрытых изменений")
    void deleteRejectsHiddenMutationPayload() {
        assertCode("INVALID_PAYLOAD", EntityType.EXPENSE, Action.DELETE, Map.of("amountMinor", 1));
    }

    @Test
    @DisplayName("Архивность требует boolean")
    void archivedFlagRequiresBoolean() {
        assertCode(
                "INVALID_ARCHIVED", EntityType.CATEGORY, Action.UPDATE, Map.of("archived", "true"));
    }

    @Test
    @DisplayName("Порядок сортировки допускает границы int")
    void sortOrderAcceptsBothSignedIntBounds() {
        for (int order : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(
                    order,
                    rules.normalize(EntityType.CATEGORY, Action.UPDATE, Map.of("sortOrder", order))
                            .get("sortOrder"));
        }
    }

    @Test
    @DisplayName("Переполнение порядка отклоняется")
    void sortOrderRejectsOverflow() {
        assertCode(
                "INVALID_SORT_ORDER",
                EntityType.CATEGORY,
                Action.UPDATE,
                Map.of("sortOrder", (long) Integer.MAX_VALUE + 1));
    }

    @Test
    @DisplayName("Неверный UUID категории отклоняется")
    void categoryIdCannotBeMalformed() {
        assertCode(
                "INVALID_CATEGORY", EntityType.EXPENSE, Action.UPDATE, Map.of("categoryId", "bad"));
    }

    @Test
    @DisplayName("Число не становится UUID категории")
    void categoryIdCannotBeCoercedFromNumber() {
        assertCode("INVALID_CATEGORY", EntityType.EXPENSE, Action.UPDATE, Map.of("categoryId", 1));
    }

    private void assertCode(String code, EntityType type, Action action, Map<String, Object> data) {
        assertEquals(
                code,
                assertThrows(DomainRejection.class, () -> rules.normalize(type, action, data))
                        .getCode());
    }
}
