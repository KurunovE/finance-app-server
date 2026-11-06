package com.financetracker.model.dto.sync;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Builder;

@Builder
public record RecoveryAckDto(
    @Min(0)
    long lastCursor,

    @AssertTrue
    boolean localCopyPreserved,

    @AssertTrue
    boolean pendingOperationsQuarantined
) {

}
