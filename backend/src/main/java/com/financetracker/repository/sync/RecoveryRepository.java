package com.financetracker.repository.sync;

import java.util.UUID;

import com.financetracker.model.entity.sync.RecoveryStateEntity;
import com.financetracker.model.entity.sync.SyncRecoveryEntity;

public interface RecoveryRepository {
    RecoveryStateEntity current();

    void lockShared();

    void lockExclusive();

    void prepare();

    boolean deviceReady(UUID user, UUID device, UUID epoch);

    SyncRecoveryEntity pending(UUID user, UUID device, UUID epoch);

    SyncRecoveryEntity lockSession(UUID user, UUID device, UUID id);

    void clearAcknowledged(UUID user, UUID device);

    void insert(UUID user, UUID device, SyncRecoveryEntity session);

    void delivered(UUID user, UUID device, UUID id, long next);

    void acknowledge(UUID user, UUID device, UUID id, UUID epoch);
}
