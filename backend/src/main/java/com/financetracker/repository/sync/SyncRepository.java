package com.financetracker.repository.sync;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.financetracker.model.dto.sync.SyncOperationDto;

public interface SyncRepository {
    void lockState(UUID user);

    Long lastSequence(UUID user);

    Map<String, Object> operation(UUID user, UUID operation);

    boolean deleted(UUID user, SyncOperationDto operation);

    void appendChange(
            UUID user, SyncOperationDto operation, long version, Map<String, Object> snapshot);

    void saveOperation(UUID user, UUID operation, String hash, Map<String, Object> result);

    List<Map<String, Object>> changes(UUID user, long cursor, long cutoff, int limit);
}
