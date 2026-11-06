package com.financetracker.model.entity.finance;

import java.time.Instant;
import java.util.UUID;

public sealed interface FinancialEntity permits CategoryEntity, ExpenseEntity, MonthlyBudgetEntity, CategoryBudgetEntity {
    UUID id();

    UUID userId();

    long version();

    Instant createdAt();

    Instant updatedAt();
}
