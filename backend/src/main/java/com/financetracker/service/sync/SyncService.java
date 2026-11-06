package com.financetracker.service.sync;

import java.util.List;
import java.util.Map;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.PullResponseDto;
import com.financetracker.model.dto.sync.SyncOperationDto;

public interface SyncService {
    List<Map<String, Object>> push(
            AccessIdentityDto identity, List<SyncOperationDto> operations, String epoch);

    PullResponseDto pull(AccessIdentityDto identity, long cursor, int limit, String epoch);
}
