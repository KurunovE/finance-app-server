package com.financetracker.unit.service.sync;

import java.util.UUID;

import com.financetracker.exception.AuthFailure;
import com.financetracker.model.entity.sync.RecoveryStateEntity;
import com.financetracker.repository.sync.RecoveryRepository;
import com.financetracker.service.sync.impl.DefaultRecoveryState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class RecoveryStateTest {
    final UUID epoch = UUID.randomUUID();
    @Mock RecoveryRepository repository;
    @InjectMocks DefaultRecoveryState state;

    void ready() {
        Mockito.when(repository.current())
                .thenReturn(RecoveryStateEntity.builder().epoch(epoch).phase("READY").build());
    }

    @Test
    @DisplayName("Отсутствие metadata закрывает API")
    void missingMetadataFailsClosed() {
        assertFailure(503, "RECOVERY_MAINTENANCE", () -> state.current());
    }

    @Test
    @DisplayName("Recovery не считается готовностью")
    void maintenanceCannotBeTreatedAsReady() {
        Mockito.when(repository.current())
                .thenReturn(RecoveryStateEntity.builder().epoch(epoch).phase("RECOVERY").build());
        assertFailure(503, "RECOVERY_MAINTENANCE", () -> state.ready());
    }

    @Test
    @DisplayName("Заголовок эпохи обязателен")
    void missingHeaderCannotFallBackToCurrentEpoch() {
        assertFailure(428, "RECOVERY_EPOCH_REQUIRED", () -> state.require(null, false));
    }

    @Test
    @DisplayName("Неверный формат эпохи отклоняется")
    void malformedHeaderHasDistinctClientError() {
        assertFailure(400, "INVALID_RECOVERY_EPOCH", () -> state.require("bad", false));
    }

    @Test
    @DisplayName("Старая эпоха не использует прежний cursor")
    void staleEpochCannotReuseNumericallyValidCursor() {
        ready();
        assertFailure(
                409,
                "RECOVERY_EPOCH_MISMATCH",
                () -> state.require(UUID.randomUUID().toString(), false));
        Mockito.verify(repository, Mockito.never()).lockShared();
    }

    @Test
    @DisplayName("Чтение не захватывает writer fence")
    void matchingReadEpochDoesNotTakeWriterFence() {
        ready();
        assertEquals(epoch, state.require(epoch.toString(), false).epoch());
        Mockito.verify(repository, Mockito.never()).lockShared();
    }

    @Test
    @DisplayName("Writer fence требует транзакцию")
    void writerFenceRequiresTransactionRatherThanTransientLock() {
        assertThrows(IllegalStateException.class, () -> state.fence());
        Mockito.verify(repository, Mockito.never()).lockShared();
    }

    @Test
    @DisplayName("Эпоха проверяется после блокировки")
    void writerChecksEpochAfterAcquiringTransactionFence() {
        ready();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            assertFailure(
                    409,
                    "RECOVERY_EPOCH_MISMATCH",
                    () -> state.require(UUID.randomUUID().toString(), true));
            Mockito.verify(repository).lockShared();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("Устройство без ack не синхронизируется")
    void unacknowledgedDeviceCannotResumeNormalSync() {
        assertFailure(
                409,
                "FULL_RESYNC_REQUIRED",
                () -> state.requireDevice(UUID.randomUUID(), UUID.randomUUID(), epoch));
    }

    @Test
    @DisplayName("Устройство с ack продолжает sync")
    void acknowledgedDeviceMayResumeNormalSync() {
        Mockito.when(
                        repository.deviceReady(
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.eq(epoch)))
                .thenReturn(true);
        assertDoesNotThrow(() -> state.requireDevice(UUID.randomUUID(), UUID.randomUUID(), epoch));
    }

    private void assertFailure(int status, String code, Executable call) {
        var failure = assertThrows(AuthFailure.class, call);
        assertEquals(status, failure.status());
        assertEquals(code, failure.getMessage());
    }
}
