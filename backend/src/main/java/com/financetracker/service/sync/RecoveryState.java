package com.financetracker.service.sync;

import java.util.UUID;

import com.financetracker.model.entity.sync.RecoveryStateEntity;

public interface RecoveryState {
    RecoveryStateEntity current();

    RecoveryStateEntity ready();

    RecoveryStateEntity fence();

    UUID parse(String value);

    RecoveryStateEntity require(String header, boolean write);

    boolean deviceReady(UUID user, UUID device, UUID epoch);

    void requireDevice(UUID user, UUID device, UUID epoch);
}
