package com.financetracker.model.entity.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import lombok.Builder;

@Builder
public record MonthlyBudgetEntity(
        UUID id,
        UUID userId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        LocalDate monthStart,
        long limitMinor
) implements FinancialEntity {

}
