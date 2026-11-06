package com.financetracker.repository.finance;

import java.util.Map;
import java.util.UUID;

import com.financetracker.model.enums.EntityType;

public interface FinanceRepository {
    Map<String, Object> find(EntityType type, UUID user, UUID id);

    Map<String, Object> logicalConflict(
            EntityType type, UUID user, UUID id, Map<String, Object> data, String normalizedName);

    void create(
            EntityType type, UUID user, UUID id, Map<String, Object> data, String normalizedName);

    int update(
            EntityType type,
            UUID user,
            UUID id,
            long expected,
            Map<String, Object> data,
            String normalizedName);

    int delete(EntityType type, UUID user, UUID id, long expected);
}
