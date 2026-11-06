package com.financetracker.service.sync.impl;

import java.util.UUID;

import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.model.entity.sync.RecoveryStateEntity;
import com.financetracker.repository.sync.RecoveryRepository;
import com.financetracker.service.sync.RecoveryState;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public final class DefaultRecoveryState implements RecoveryState {
    private static final String RECOVERY_FENCE_REQUIRES_A_TRANSACTION = "Recovery fence requires a transaction";

    private final RecoveryRepository repository;

    @Override
    public RecoveryStateEntity current() {
        var state = repository.current();

        if (state == null) {
            throw AuthFailure.builder()
                    .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                    .code(ErrorCodes.RECOVERY_MAINTENANCE)
                    .build();
        }

        return state;
    }

    @Override
    public RecoveryStateEntity ready() {
        var state = current();

        if (!state.phase().equals(StatusValues.READY)) {
            throw AuthFailure.builder()
                    .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                    .code(ErrorCodes.RECOVERY_MAINTENANCE)
                    .build();
        }

        return state;
    }

    @Override
    public RecoveryStateEntity fence() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(RECOVERY_FENCE_REQUIRES_A_TRANSACTION);
        }

        repository.lockShared();

        return ready();
    }

    @Override
    public UUID parse(String value) {
        if (value == null) {
            throw AuthFailure.builder()
                    .status(HttpStatus.PRECONDITION_REQUIRED.value())
                    .code(ErrorCodes.RECOVERY_EPOCH_REQUIRED)
                    .build();
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            throw AuthFailure.builder()
                    .status(HttpStatus.BAD_REQUEST.value())
                    .code(ErrorCodes.INVALID_RECOVERY_EPOCH)
                    .build();
        }
    }

    @Override
    public RecoveryStateEntity require(String header, boolean write) {
        UUID epoch = parse(header);
        RecoveryStateEntity current = write ? fence() : ready();

        if (!epoch.equals(current.epoch())) {
            throw AuthFailure.builder()
                    .status(HttpStatus.CONFLICT.value())
                    .code(ErrorCodes.RECOVERY_EPOCH_MISMATCH)
                    .build();
        }

        return current;
    }

    @Override
    public boolean deviceReady(UUID user, UUID device, UUID epoch) {
        return repository.deviceReady(user, device, epoch);
    }

    @Override
    public void requireDevice(UUID user, UUID device, UUID epoch) {
        if (!deviceReady(user, device, epoch)) {
            throw AuthFailure.builder()
                    .status(HttpStatus.CONFLICT.value())
                    .code(ErrorCodes.FULL_RESYNC_REQUIRED)
                    .build();
        }
    }
}
