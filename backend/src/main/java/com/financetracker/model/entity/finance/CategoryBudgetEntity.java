package com.financetracker.model.entity.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import lombok.Builder;

@Builder
public record CategoryBudgetEntity(
        UUID id,
        UUID userId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        UUID categoryId,
        LocalDate monthStart,
        long limitMinor
) implements FinancialEntity {

}
