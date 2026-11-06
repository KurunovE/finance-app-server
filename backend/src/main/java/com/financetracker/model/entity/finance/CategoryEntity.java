package com.financetracker.model.entity.finance;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

@Builder
public record CategoryEntity(
        UUID id,
        UUID userId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String name,
        String normalizedName,
        boolean archived,
        int sortOrder
) implements FinancialEntity {

}
