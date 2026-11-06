package com.financetracker.service.sync.impl;

import java.util.ArrayList;
import java.util.UUID;

import com.financetracker.config.BeanNames;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.RecoveryPageDto;
import com.financetracker.model.dto.sync.RecoverySessionDto;
import com.financetracker.model.dto.sync.RecoveryStatusDto;
import com.financetracker.model.entity.sync.SyncRecoveryEntity;
import com.financetracker.policy.SyncPolicy;
import com.financetracker.repository.context.UserTransactionContext;
import com.financetracker.repository.sync.RecoveryRepository;
import com.financetracker.repository.sync.SyncRepository;
import com.financetracker.service.auth.AuthService;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.service.sync.RecoverySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@RequiredArgsConstructor
@Service
public final class DefaultRecoverySyncService implements RecoverySyncService {

    private final RecoveryRepository repository;
    private final SyncRepository syncRepository;
    private final RecoveryState state;
    private final AuthService auth;
    private final UserTransactionContext context;

    @Qualifier(BeanNames.WRITE_TRANSACTION)
    private final TransactionOperations transaction;

    @Override
    public RecoveryStatusDto status(AccessIdentityDto identity) {
        return transaction.execute(
                tx -> {
                    var current = state.fence();
                    auth.me(identity);
                    context.setUser(identity.userId());
                    long last = syncRepository.lastSequence(identity.userId());

                    return RecoveryStatusDto.builder()
                            .epoch(current.epoch())
                            .phase(current.phase())
                            .requiresFullResync(
                                    !state.deviceReady(
                                            identity.userId(),
                                            identity.deviceId(),
                                            current.epoch()))
                            .latestSeq(last)
                            .build();
                });
    }

    @Override
    public RecoverySessionDto start(AccessIdentityDto identity, String epoch) {
        return transaction.execute(
                tx -> {
                    var current = state.require(epoch, true);
                    auth.me(identity);
                    context.setUser(identity.userId());
                    var existing = repository.pending(identity.userId(), identity.deviceId(), current.epoch());

                    if (existing != null) {
                        return sessionDto(existing);
                    }

                    repository.clearAcknowledged(identity.userId(), identity.deviceId());
                    long cutoff = syncRepository.lastSequence(identity.userId());
                    var session = SyncRecoveryEntity.builder().recoveryId(UUID.randomUUID()).epoch(current.epoch()).cutoffSeq(cutoff).deliveredSeq(0).build();
                    repository.insert(identity.userId(), identity.deviceId(), session);

                    return sessionDto(session);
                });
    }

    private RecoverySessionDto sessionDto(SyncRecoveryEntity session) {
        return RecoverySessionDto.builder()
                .recoveryId(session.recoveryId())
                .epoch(session.epoch())
                .cutoffSeq(session.cutoffSeq())
                .deliveredSeq(session.deliveredSeq())
                .build();
    }

    private SyncRecoveryEntity locked(AccessIdentityDto identity, UUID id, UUID epoch) {
        var session = repository.lockSession(identity.userId(), identity.deviceId(), id);

        if (session == null) {
            throw AuthFailure.builder()
                    .status(HttpStatus.NOT_FOUND.value())
                    .code(ErrorCodes.RECOVERY_NOT_FOUND)
                    .build();
        }

        if (!session.epoch().equals(epoch)) {
            throw AuthFailure.builder()
                    .status(HttpStatus.CONFLICT.value())
                    .code(ErrorCodes.RECOVERY_EPOCH_MISMATCH)
                    .build();
        }

        return session;
    }

    @Override
    public RecoveryPageDto pull(
            AccessIdentityDto identity, UUID id, long cursor, int limit, String epoch) {
        if (cursor < 0 || limit < SyncPolicy.MIN_PAGE_SIZE || limit > SyncPolicy.MAX_PAGE_SIZE) {
            throw AuthFailure.builder()
                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                    .code(ErrorCodes.INVALID_CURSOR_OR_LIMIT)
                    .build();
        }

        return transaction.execute(
                tx -> {
                    var current = state.require(epoch, true);
                    auth.me(identity);
                    context.setUser(identity.userId());
                    var session = locked(identity, id, current.epoch());

                    if (cursor > session.deliveredSeq() || cursor > session.cutoffSeq()) {
                        throw AuthFailure.builder()
                                .status(HttpStatus.CONFLICT.value())
                                .code(ErrorCodes.RECOVERY_CURSOR_SKIPPED)
                                .build();
                    }

                    var changes = syncRepository.changes(identity.userId(), cursor, session.cutoffSeq(), limit + SyncPolicy.LOOKAHEAD);
                    boolean more = changes.size() > limit;
                    var page = more ? new ArrayList<>(changes.subList(0, limit)) : changes;
                    long next = page.isEmpty() ? cursor : ((Number) page.getLast().get(JsonFields.SEQ)).longValue();
                    repository.delivered(identity.userId(), identity.deviceId(), id, next);

                    return RecoveryPageDto.builder()
                            .recoveryId(id)
                            .epoch(current.epoch())
                            .cutoffSeq(session.cutoffSeq())
                            .changes(page)
                            .nextCursor(next)
                            .hasMore(more)
                            .build();
                });
    }

    @Override
    public void acknowledge(AccessIdentityDto identity, UUID id, long lastCursor, String epoch) {
        transaction.executeWithoutResult(
                tx -> {
                    var current = state.require(epoch, true);
                    auth.me(identity);
                    context.setUser(identity.userId());
                    var session = locked(identity, id, current.epoch());

                    if (lastCursor != session.cutoffSeq()
                            || session.deliveredSeq() != session.cutoffSeq()) {
                        throw AuthFailure.builder()
                                .status(HttpStatus.CONFLICT.value())
                                .code(ErrorCodes.RECOVERY_INCOMPLETE)
                                .build();
                    }

                    repository.acknowledge(
                            identity.userId(), identity.deviceId(), id, current.epoch());
                });
    }
}
