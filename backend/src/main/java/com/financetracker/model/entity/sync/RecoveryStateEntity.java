package com.financetracker.model.entity.sync;

import java.util.UUID;

import lombok.Builder;

@Builder
public record RecoveryStateEntity(
    UUID epoch,
    String phase
) {

}
