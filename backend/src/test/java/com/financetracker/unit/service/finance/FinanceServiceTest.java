package com.financetracker.unit.service.finance;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.financetracker.model.dto.finance.MutationDto;
import com.financetracker.model.dto.sync.SyncOperationDto;
import com.financetracker.model.enums.Action;
import com.financetracker.model.enums.EntityType;
import com.financetracker.repository.finance.FinanceRepository;
import com.financetracker.service.finance.impl.DefaultFinanceRules;
import com.financetracker.service.finance.impl.DefaultFinanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceServiceTest {
    final UUID user = UUID.randomUUID(), id = UUID.randomUUID(), category = UUID.randomUUID();
    final Map<String, Object> saved = Map.of("version", 2L, "note", "saved");
    @Mock FinanceRepository repository;
    @Spy DefaultFinanceRules rules;
    @InjectMocks DefaultFinanceService service;
    @Captor ArgumentCaptor<Map<String, Object>> payload;

    MutationDto apply(EntityType type, Action action, Long version, Map<String, Object> data) {
        return service.apply(
                user,
                SyncOperationDto.builder()
                        .operationId(UUID.randomUUID())
                        .entityType(type)
                        .entityId(id)
                        .action(action)
                        .expectedVersion(version)
                        .epoch(UUID.randomUUID())
                        .build(),
                data);
    }

    Map<String, Object> expense() {
        return Map.of(
                "version",
                1L,
                "categoryId",
                category.toString(),
                "amountMinor",
                10L,
                "occurredOn",
                "2026-10-31",
                "note",
                "old",
                "userId",
                user.toString());
    }

    void current(Map<String, Object> row) {
        when(repository.find(any(), eq(user), eq(id))).thenReturn(row).thenReturn(saved);
    }

    void noConflict() {
        when(repository.logicalConflict(any(), eq(user), eq(id), anyMap(), anyString()))
                .thenReturn(null);
    }

    void missing() {
        when(repository.find(any(), eq(user), eq(id))).thenReturn(null);
    }

    void category(boolean archived) {
        doReturn(Map.of("archived", archived))
                .when(repository)
                .find(EntityType.CATEGORY, user, category);
    }

    void updateSucceeds() {
        when(repository.update(any(), eq(user), eq(id), anyLong(), anyMap(), anyString()))
                .thenReturn(1);
    }

    void rejected(MutationDto result, String status, String code) {
        assertEquals(status, result.status());
        assertEquals(code, result.code());
        verify(repository, never()).create(any(), any(), any(), anyMap(), anyString());
        verify(repository, never()).update(any(), any(), any(), anyLong(), anyMap(), anyString());
        verify(repository, never()).delete(any(), any(), any(), anyLong());
    }

    static Stream<Arguments> invalidVersions() {
        return Stream.of(
                Arguments.of(Action.CREATE, 1L),
                Arguments.of(Action.UPDATE, null),
                Arguments.of(Action.UPDATE, 0L),
                Arguments.of(Action.DELETE, -1L));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Неверная версия не достигает репозитория")
    @MethodSource("invalidVersions")
    void invalidVersionCannotReadOrMutateRepository(Action action, Long version) {
        rejected(
                apply(EntityType.EXPENSE, action, version, Map.of()),
                "VALIDATION_ERROR",
                "INVALID_VERSION");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Чужая или отсутствующая запись не изменяется")
    @EnumSource(
            value = Action.class,
            names = {"UPDATE", "DELETE"})
    void absentOrForeignEntityCannotBeChanged(Action action) {
        missing();
        var result = apply(EntityType.EXPENSE, action, 1L, Map.of());
        rejected(result, "NOT_FOUND", "NOT_FOUND");
        assertNull(result.snapshot());
    }

    @Test
    @DisplayName("Создание не перезаписывает запись")
    void createCannotOverwriteExistingEntity() {
        current(expense());
        var result = apply(EntityType.EXPENSE, Action.CREATE, null, Map.of());
        rejected(result, "CONFLICT", "ENTITY_EXISTS");
        assertEquals(expense(), result.snapshot());
    }

    @Test
    @DisplayName("Старая версия возвращает конфликт без записи")
    void staleVersionReturnsCurrentSnapshotWithoutWriting() {
        current(expense());
        var result = apply(EntityType.EXPENSE, Action.UPDATE, 2L, Map.of("amountMinor", 20L));
        rejected(result, "CONFLICT", "VERSION_CONFLICT");
        assertEquals(1L, result.version());
        assertEquals(expense(), result.snapshot());
    }

    @Test
    @DisplayName("Максимальная версия не переполняется")
    void maximumVersionCannotOverflow() {
        current(Map.of("version", Long.MAX_VALUE));
        rejected(
                apply(EntityType.EXPENSE, Action.UPDATE, Long.MAX_VALUE, Map.of()),
                "VALIDATION_ERROR",
                "VERSION_OVERFLOW");
    }

    @Test
    @DisplayName("Удаление категории запрещено")
    void categoryMustBeArchivedRatherThanDeleted() {
        current(Map.of("version", 1L));
        rejected(
                apply(EntityType.CATEGORY, Action.DELETE, 1L, Map.of()),
                "VALIDATION_ERROR",
                "ARCHIVE_CATEGORY");
    }

    @Test
    @DisplayName("Удаление расхода создаёт версию tombstone")
    void expenseDeleteReturnsNewTombstoneVersionWithoutSnapshot() {
        current(expense());
        when(repository.delete(EntityType.EXPENSE, user, id, 1L)).thenReturn(1);
        var result = apply(EntityType.EXPENSE, Action.DELETE, 1L, Map.of());
        assertEquals("APPLIED", result.status());
        assertEquals(2L, result.version());
        assertNull(result.snapshot());
        verify(repository).delete(EntityType.EXPENSE, user, id, 1L);
    }

    @Test
    @DisplayName("Обновление сохраняет только бизнес-поля")
    void partialUpdatePreservesUnchangedBusinessFieldsButNotOwnershipMetadata() {
        noConflict();
        current(expense());
        category(false);
        updateSucceeds();
        assertEquals(
                "APPLIED",
                apply(EntityType.EXPENSE, Action.UPDATE, 1L, Map.of("note", "edited")).status());
        verify(repository)
                .update(
                        eq(EntityType.EXPENSE),
                        eq(user),
                        eq(id),
                        eq(1L),
                        payload.capture(),
                        eq(""));
        assertEquals(10L, payload.getValue().get("amountMinor"));
        assertEquals("2026-10-31", payload.getValue().get("occurredOn"));
        assertEquals("edited", payload.getValue().get("note"));
        assertFalse(payload.getValue().containsKey("userId"));
        assertFalse(payload.getValue().containsKey("version"));
    }

    @Test
    @DisplayName("Явный null очищает сохранённую заметку")
    void explicitNullNoteClearsStoredNote() {
        noConflict();
        current(expense());
        category(false);
        updateSucceeds();
        var patch = new HashMap<String, Object>();
        patch.put("note", null);
        assertEquals("APPLIED", apply(EntityType.EXPENSE, Action.UPDATE, 1L, patch).status());
        verify(repository)
                .update(
                        eq(EntityType.EXPENSE),
                        eq(user),
                        eq(id),
                        eq(1L),
                        payload.capture(),
                        eq(""));
        assertTrue(payload.getValue().containsKey("note"));
        assertNull(payload.getValue().get("note"));
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @DisplayName("Ссылка на категорию проверяется по владельцу")
    @EnumSource(
            value = EntityType.class,
            names = {"EXPENSE", "CATEGORY_BUDGET"})
    void categoryReferenceMustBelongToTheSameUser(EntityType type) {
        missing();
        when(repository.find(EntityType.CATEGORY, user, category)).thenReturn(null);
        rejected(
                apply(type, Action.CREATE, null, Map.of("categoryId", category.toString())),
                "VALIDATION_ERROR",
                "INVALID_CATEGORY");
        verify(repository).find(EntityType.CATEGORY, user, category);
    }

    @Test
    @DisplayName("Новый расход запрещён в архивной категории")
    void newExpenseCannotUseArchivedCategory() {
        missing();
        category(true);
        rejected(
                apply(
                        EntityType.EXPENSE,
                        Action.CREATE,
                        null,
                        Map.of("categoryId", category.toString())),
                "VALIDATION_ERROR",
                "CATEGORY_ARCHIVED");
    }

    @Test
    @DisplayName("Перенос в архивную категорию запрещён")
    void expenseCannotMoveToArchivedCategory() {
        var old = new HashMap<>(expense());
        old.put("categoryId", UUID.randomUUID().toString());
        current(old);
        category(true);
        rejected(
                apply(
                        EntityType.EXPENSE,
                        Action.UPDATE,
                        1L,
                        Map.of("categoryId", category.toString())),
                "VALIDATION_ERROR",
                "CATEGORY_ARCHIVED");
    }

    @Test
    @DisplayName("Исторический расход разрешено редактировать")
    void historicalExpenseMayBeEditedWithinItsArchivedCategory() {
        noConflict();
        current(expense());
        category(true);
        updateSucceeds();
        assertEquals(
                "APPLIED",
                apply(EntityType.EXPENSE, Action.UPDATE, 1L, Map.of("amountMinor", 20L)).status());
    }

    @Test
    @DisplayName("Бюджет допускает архивную категорию")
    void categoryBudgetMayStillReferToArchivedCategory() {
        noConflict();
        current(null);
        category(true);
        assertEquals(
                "APPLIED",
                apply(
                                EntityType.CATEGORY_BUDGET,
                                Action.CREATE,
                                null,
                                Map.of(
                                        "categoryId",
                                        category.toString(),
                                        "monthStart",
                                        "2026-10-01",
                                        "limitMinor",
                                        1L))
                        .status());
        verify(repository)
                .create(eq(EntityType.CATEGORY_BUDGET), eq(user), eq(id), anyMap(), eq(""));
    }

    @Test
    @DisplayName("Логический дубликат возвращает каноническую запись")
    void logicalDuplicateReturnsCanonicalEntityWithoutWriting() {
        missing();
        var duplicate = Map.<String, Object>of("version", 5L, "name", "Food");
        when(repository.logicalConflict(
                        eq(EntityType.CATEGORY), eq(user), eq(id), anyMap(), eq("food")))
                .thenReturn(duplicate);
        var result = apply(EntityType.CATEGORY, Action.CREATE, null, Map.of("name", "Food"));
        rejected(result, "CONFLICT", "LOGICAL_KEY_CONFLICT");
        assertEquals(duplicate, result.snapshot());
        assertEquals(5L, result.version());
    }

    @Test
    @DisplayName("Создание категории возвращает сохранённый snapshot")
    void categoryCreatePassesCanonicalNameAndReturnsPersistedSnapshot() {
        noConflict();
        current(null);
        var result = apply(EntityType.CATEGORY, Action.CREATE, null, Map.of("name", "FOOD"));
        verify(repository).create(eq(EntityType.CATEGORY), eq(user), eq(id), anyMap(), eq("food"));
        assertEquals(saved, result.snapshot());
        assertEquals(2L, result.version());
    }

    @Test
    @DisplayName("Несостоявшееся обновление не сообщает успех")
    void failedOptimisticUpdateCannotReportSuccess() {
        noConflict();
        current(expense());
        category(false);
        assertThrows(
                IllegalStateException.class,
                () -> apply(EntityType.EXPENSE, Action.UPDATE, 1L, Map.of("note", "edited")));
    }

    @Test
    @DisplayName("Несостоявшееся удаление не создаёт tombstone")
    void failedDeleteCannotReportTombstone() {
        current(expense());
        assertThrows(
                IllegalStateException.class,
                () -> apply(EntityType.EXPENSE, Action.DELETE, 1L, Map.of()));
    }
}
