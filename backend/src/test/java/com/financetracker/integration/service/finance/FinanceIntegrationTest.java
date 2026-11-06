package com.financetracker.integration.service.finance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.financetracker.model.dto.finance.MutationDto;
import com.financetracker.model.dto.sync.SyncOperationDto;
import com.financetracker.model.enums.Action;
import com.financetracker.model.enums.EntityType;
import com.financetracker.repository.context.UserTransactionContext;
import com.financetracker.service.finance.FinanceRules;
import com.financetracker.service.finance.FinanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(
        classes = FinancePersistenceTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("finance-repository-slice")
class FinanceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired FinanceService finance;
    @Autowired FinanceRules rules;
    @Autowired UserTransactionContext context;
    private UUID user = UUID.randomUUID(), other = UUID.randomUUID(), category = UUID.randomUUID();

    @BeforeEach
    void seed() {
        for (UUID id : List.of(user, other)) {
            jdbc.update(
                    """
                    INSERT INTO app.users(id, email, email_norm, password_hash, base_currency_code)
                    VALUES (?, ?, ?, 'test', 'RUB')
                    """,
                    id,
                    id + "@example.test",
                    id + "@example.test");
        }

        assertEquals(
                "APPLIED",
                apply(EntityType.CATEGORY, category, Action.CREATE, null, Map.of("name", "Food"))
                        .status());
    }

    private MutationDto apply(
            EntityType type, UUID id, Action action, Long expected, Map<String, Object> data) {
        return tx.execute(
                status -> {
                    context.setUser(user);

                    return finance.apply(
                            user,
                            SyncOperationDto.builder()
                                    .operationId(UUID.randomUUID())
                                    .entityType(type)
                                    .entityId(id)
                                    .action(action)
                                    .expectedVersion(expected)
                                    .data(data)
                                    .epoch(UUID.randomUUID())
                                    .build(),
                            rules.normalize(type, action, data));
                });
    }

    @Test
    @DisplayName("Расходы соблюдают версии и архивирование")
    void expenseVersionArchiveAndDeleteRulesHold() {
        UUID expense = UUID.randomUUID();
        var data = Map.<String, Object>of("categoryId", category.toString(), "amountMinor", Long.MAX_VALUE, "occurredOn", "2024-02-29");
        assertEquals(1L, apply(EntityType.EXPENSE, expense, Action.CREATE, null, data).version());
        assertEquals(
                "ENTITY_EXISTS",
                apply(EntityType.EXPENSE, expense, Action.CREATE, null, data).code());
        assertEquals(
                2L,
                apply(
                                EntityType.EXPENSE,
                                expense,
                                Action.UPDATE,
                                1L,
                                Map.of("note", "safe'; DROP TABLE app.users; --"))
                        .version());
        assertEquals(
                "CONFLICT",
                apply(EntityType.EXPENSE, expense, Action.UPDATE, 1L, Map.of("amountMinor", 2))
                        .status());
        assertEquals(
                "INVALID_VERSION",
                apply(EntityType.EXPENSE, expense, Action.UPDATE, null, Map.of("amountMinor", 2))
                        .code());
        assertEquals(
                "INVALID_VERSION",
                apply(EntityType.EXPENSE, expense, Action.CREATE, 1L, data).code());
        assertEquals(
                "APPLIED",
                apply(EntityType.CATEGORY, category, Action.UPDATE, 1L, Map.of("archived", true))
                        .status());
        assertEquals(
                "CATEGORY_ARCHIVED",
                apply(EntityType.EXPENSE, UUID.randomUUID(), Action.CREATE, null, data).code());
        assertEquals(
                3L,
                apply(EntityType.EXPENSE, expense, Action.UPDATE, 2L, Map.of("amountMinor", 2))
                        .version());
        assertEquals(
                "ARCHIVE_CATEGORY",
                apply(EntityType.CATEGORY, category, Action.DELETE, 2L, null).code());
        assertEquals(4L, apply(EntityType.EXPENSE, expense, Action.DELETE, 3L, null).version());
        assertEquals(
                "NOT_FOUND", apply(EntityType.EXPENSE, expense, Action.DELETE, 3L, null).status());
    }

    @Test
    @DisplayName("Бюджеты соблюдают ключи и владельца категории")
    void budgetsEnforceLogicalKeysAndForeignCategories() {
        UUID monthly = UUID.randomUUID(), budget = UUID.randomUUID();
        var month = Map.<String, Object>of("monthStart", "2026-10-01", "limitMinor", 1000);
        assertEquals(
                "APPLIED",
                apply(EntityType.MONTHLY_BUDGET, monthly, Action.CREATE, null, month).status());
        assertEquals(
                monthly.toString(),
                apply(EntityType.MONTHLY_BUDGET, UUID.randomUUID(), Action.CREATE, null, month)
                        .snapshot()
                        .get("id"));
        assertEquals(
                2L,
                apply(
                                EntityType.MONTHLY_BUDGET,
                                monthly,
                                Action.UPDATE,
                                1L,
                                Map.of("limitMinor", 2000))
                        .version());
        assertEquals(
                3L, apply(EntityType.MONTHLY_BUDGET, monthly, Action.DELETE, 2L, null).version());
        var cat = new HashMap<>(month);
        cat.put("categoryId", category.toString());
        assertEquals(
                "APPLIED",
                apply(EntityType.CATEGORY_BUDGET, budget, Action.CREATE, null, cat).status());
        assertEquals(
                "LOGICAL_KEY_CONFLICT",
                apply(EntityType.CATEGORY_BUDGET, UUID.randomUUID(), Action.CREATE, null, cat)
                        .code());
        assertEquals(
                2L,
                apply(
                                EntityType.CATEGORY_BUDGET,
                                budget,
                                Action.UPDATE,
                                1L,
                                Map.of("limitMinor", 2000))
                        .version());
        assertEquals(
                3L, apply(EntityType.CATEGORY_BUDGET, budget, Action.DELETE, 2L, null).version());
        assertEquals(
                "LOGICAL_KEY_CONFLICT",
                apply(
                                EntityType.CATEGORY,
                                UUID.randomUUID(),
                                Action.CREATE,
                                null,
                                Map.of("name", " FOOD "))
                        .code());
        UUID foreign = UUID.randomUUID();
        tx.executeWithoutResult(
                status -> {
                    context.setUser(other);
                    jdbc.update(
                            """
                            INSERT INTO app.categories(user_id, id, name, normalized_name)
                            VALUES (?, ?, 'Only B', 'only b')
                            """,
                            other,
                            foreign);
                });
        assertEquals(
                "INVALID_CATEGORY",
                apply(
                                EntityType.EXPENSE,
                                UUID.randomUUID(),
                                Action.CREATE,
                                null,
                                Map.of(
                                        "categoryId",
                                        foreign.toString(),
                                        "amountMinor",
                                        1,
                                        "occurredOn",
                                        "2026-10-31"))
                        .code());
        assertEquals(
                "NOT_FOUND",
                apply(EntityType.CATEGORY, foreign, Action.UPDATE, 1L, Map.of("name", "stolen"))
                        .status());
    }

    @Test
    @DisplayName("Версии и деньги не переполняются")
    void versionOverflowAndBigMoneyCannotSilentlyWrap() {
        UUID expense = UUID.randomUUID();
        apply(
                EntityType.EXPENSE,
                expense,
                Action.CREATE,
                null,
                Map.of(
                        "categoryId",
                        category.toString(),
                        "amountMinor",
                        Long.MAX_VALUE,
                        "occurredOn",
                        "2026-10-31"));
        apply(
                EntityType.EXPENSE,
                UUID.randomUUID(),
                Action.CREATE,
                null,
                Map.of(
                        "categoryId",
                        category.toString(),
                        "amountMinor",
                        Long.MAX_VALUE,
                        "occurredOn",
                        "2026-10-31"));
        tx.executeWithoutResult(
                status -> {
                    context.setUser(user);
                    var sum = jdbc.queryForObject(
                            """
                    SELECT sum(amount_minor)
                    FROM app.expenses
                    WHERE user_id = ?
                    """,
                            BigDecimal.class,
                            user);
                    assertEquals(
                            new BigDecimal(
                                    BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)),
                            sum);
                    assertThrows(ArithmeticException.class, sum::longValueExact);
                    jdbc.update(
                            """
                            UPDATE app.expenses
                            SET version = ?
                            WHERE user_id = ?
                                AND id = ?
                            """,
                            Long.MAX_VALUE,
                            user,
                            expense);
                });
        assertEquals(
                "VERSION_OVERFLOW",
                apply(EntityType.EXPENSE, expense, Action.DELETE, Long.MAX_VALUE, null).code());
    }
}
