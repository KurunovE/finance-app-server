package com.financetracker.model.entity.sync;

import java.util.UUID;

import lombok.Builder;

@Builder
public record SyncRecoveryEntity(
    UUID recoveryId,
    UUID epoch,
    long cutoffSeq,
    long deliveredSeq
) {

}
