package com.financetracker.service.sync.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.financetracker.config.BeanNames;
import com.financetracker.exception.AuthFailure;
import com.financetracker.exception.DomainRejection;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.finance.MutationDto;
import com.financetracker.model.dto.sync.PreparedOperationDto;
import com.financetracker.model.dto.sync.PullResponseDto;
import com.financetracker.model.dto.sync.SyncOperationDto;
import com.financetracker.model.enums.Action;
import com.financetracker.policy.SyncPolicy;
import com.financetracker.repository.context.UserTransactionContext;
import com.financetracker.repository.sync.SyncRepository;
import com.financetracker.security.token.CryptoTokens;
import com.financetracker.service.auth.AuthService;
import com.financetracker.service.finance.FinanceRules;
import com.financetracker.service.finance.FinanceService;
import com.financetracker.service.sync.RecoveryState;
import com.financetracker.service.sync.SyncService;
import com.financetracker.support.json.SyncJson;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@RequiredArgsConstructor
@Service
public final class DefaultSyncService implements SyncService {

    private final SyncRepository repository;
    private final FinanceRules rules;
    private final FinanceService finance;
    private final AuthService auth;
    private final UserTransactionContext context;
    private final SyncJson json;
    private final RecoveryState recovery;

    @Qualifier(BeanNames.WRITE_TRANSACTION)
    private final TransactionOperations write;

    @Qualifier(BeanNames.SYNC_READ_TRANSACTION)
    private final TransactionOperations read;

    @Override
    public List<Map<String, Object>> push(
            AccessIdentityDto identity, List<SyncOperationDto> operations, String epoch) {
        var current = recovery.require(epoch, false);

        if (operations.stream().anyMatch(op -> !op.epoch().equals(current.epoch()))) {
            throw AuthFailure.builder()
                    .status(HttpStatus.CONFLICT.value())
                    .code(ErrorCodes.OPERATION_EPOCH_MISMATCH)
                    .build();
        }

        // Reject malformed/deep batches before committing any operation; business errors are
        // per-operation.
        var prepared = operations.stream().map(this::prepare).toList();
        var results = new ArrayList<Map<String, Object>>();

        for (var item : prepared) {
            results.add(
                    write.execute(
                            status -> {
                                var locked = recovery.require(epoch, true);
                                recovery.requireDevice(
                                        identity.userId(), identity.deviceId(), locked.epoch());

                                return apply(identity, item);
                            }));
        }

        return results;
    }

    private PreparedOperationDto prepare(SyncOperationDto operation) {
        Object raw = json.canonical(operation.data());
        Map<String, Object> normalized = null;
        String error = null;

        try {
            normalized =
                    rules.normalize(operation.entityType(), operation.action(), operation.data());
        } catch (DomainRejection rejected) {
            error = rejected.getCode();
        }

        var canonical = new TreeMap<String, Object>();
        canonical.put(JsonFields.ENTITY_TYPE, operation.entityType());
        canonical.put(JsonFields.ENTITY_ID, operation.entityId());
        canonical.put(JsonFields.ACTION, operation.action());
        canonical.put(JsonFields.EPOCH, operation.epoch());
        canonical.put(JsonFields.EXPECTED_VERSION, operation.expectedVersion());
        canonical.put(JsonFields.DATA, normalized == null ? raw : json.canonical(normalized));

        return PreparedOperationDto.builder()
                .operation(operation)
                .normalized(normalized)
                .hash(CryptoTokens.hash(json.write(canonical)))
                .error(error)
                .build();
    }

    private Map<String, Object> apply(AccessIdentityDto identity, PreparedOperationDto prepared) {
        auth.me(identity); // Joins this transaction, locks user before state; races with
        // disable/delete/logout safely.
        UUID user = identity.userId();
        context.setUser(user);
        repository.lockState(user);
        var op = prepared.operation();
        var row = repository.operation(user, op.operationId());

        if (row != null) {
            if (!row.get(JsonFields.REQUEST_HASH).equals(prepared.hash())) {
                throw AuthFailure.builder()
                        .status(HttpStatus.CONFLICT.value())
                        .code(ErrorCodes.OPERATION_ID_REUSED)
                        .build();
            }

            var result = json.read((String) row.get(JsonFields.RESULT_PAYLOAD));

            if (result.get(JsonFields.STATUS).equals(StatusValues.APPLIED)) {
                result.put(JsonFields.STATUS, StatusValues.ALREADY_APPLIED);
            }

            return result;
        }

        MutationDto mutation;

        if (prepared.error() != null) {
            mutation =
                    MutationDto.builder()
                            .status(StatusValues.VALIDATION_ERROR)
                            .version(null)
                            .snapshot(null)
                            .code(prepared.error())
                            .build();
        } else if (op.action() == Action.CREATE && repository.deleted(user, op)) {
            mutation =
                    MutationDto.builder()
                            .status(StatusValues.CONFLICT)
                            .version(null)
                            .snapshot(null)
                            .code(ErrorCodes.ENTITY_ID_REUSED)
                            .build();
        } else {
            mutation = finance.apply(user, op, prepared.normalized());
        }

        var result = new LinkedHashMap<String, Object>();
        result.put(JsonFields.OPERATION_ID, op.operationId().toString());
        result.put(JsonFields.STATUS, mutation.status());

        if (mutation.version() != null) {
            result.put(JsonFields.ENTITY_VERSION, mutation.version());
        }

        if (mutation.snapshot() != null) {
            result.put(JsonFields.SERVER_SNAPSHOT, mutation.snapshot());
        }

        if (mutation.code() != null) {
            result.put(JsonFields.CODE, mutation.code());
        }

        if (mutation.status().equals(StatusValues.APPLIED)) {
            repository.appendChange(user, op, mutation.version(), mutation.snapshot());
        }

        repository.saveOperation(user, op.operationId(), prepared.hash(), result);

        return result;
    }

    @Override
    public PullResponseDto pull(AccessIdentityDto identity, long cursor, int limit, String epoch) {
        if (cursor < 0 || limit < SyncPolicy.MIN_PAGE_SIZE || limit > SyncPolicy.MAX_PAGE_SIZE) {
            throw AuthFailure.builder()
                    .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                    .code(ErrorCodes.INVALID_CURSOR_OR_LIMIT)
                    .build();
        }

        return read.execute(
                status -> {
                    var current = recovery.require(epoch, false);
                    recovery.requireDevice(identity.userId(), identity.deviceId(), current.epoch());
                    UUID user = identity.userId();
                    context.setUser(user);
                    Long last = repository.lastSequence(user);

                    if (last == null) {
                        throw AuthFailure.unauthorized();
                    }

                    if (cursor > last) {
                        throw AuthFailure.builder()
                                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                                .code(ErrorCodes.CURSOR_AHEAD)
                                .build();
                    }

                    var changes = repository.changes(user, cursor, last, limit + SyncPolicy.LOOKAHEAD);
                    boolean more = changes.size() > limit;
                    var page = more ? new ArrayList<>(changes.subList(0, limit)) : changes;
                    long next = page.isEmpty() ? cursor : ((Number) page.getLast().get(JsonFields.SEQ)).longValue();

                    return PullResponseDto.builder()
                            .changes(page)
                            .nextCursor(next)
                            .hasMore(more)
                            .epoch(current.epoch())
                            .build();
                });
    }
}
