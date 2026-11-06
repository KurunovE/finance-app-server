package com.financetracker.model.dto.sync;

import java.util.Map;
import java.util.UUID;

import com.financetracker.model.enums.Action;
import com.financetracker.model.enums.EntityType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record SyncOperationDto(
        @NotNull
        UUID operationId,

        @NotNull
        EntityType entityType,

        @NotNull
        UUID entityId,

        @NotNull
        Action action,

        Long expectedVersion,

        Map<String, Object> data,

        @NotNull
        UUID epoch
) {
    private static final String REDACTED_DESCRIPTION = "SyncOperationDto[redacted]";

    @org.jetbrains.annotations.NotNull
    @Override
    public String toString() {
        return REDACTED_DESCRIPTION;
    }
}
