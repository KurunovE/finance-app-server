package com.financetracker.model.dto.sync;

import java.util.UUID;

import lombok.Builder;

@Builder
public record RecoveryStatusDto(
    UUID epoch,
    String phase,
    boolean requiresFullResync,
    long latestSeq
) {

}
