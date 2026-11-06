package com.financetracker.model.dto.sync;

import java.util.UUID;

import lombok.Builder;

@Builder
public record RecoverySessionDto(
    UUID recoveryId,
    UUID epoch,
    long cutoffSeq,
    long deliveredSeq
) {

}
