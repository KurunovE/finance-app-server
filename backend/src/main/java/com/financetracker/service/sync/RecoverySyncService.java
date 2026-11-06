package com.financetracker.service.sync;

import java.util.UUID;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.RecoveryPageDto;
import com.financetracker.model.dto.sync.RecoverySessionDto;
import com.financetracker.model.dto.sync.RecoveryStatusDto;

public interface RecoverySyncService {
    RecoveryStatusDto status(AccessIdentityDto identity);

    RecoverySessionDto start(AccessIdentityDto identity, String epoch);

    RecoveryPageDto pull(AccessIdentityDto identity, UUID id, long cursor, int limit, String epoch);

    void acknowledge(AccessIdentityDto identity, UUID id, long lastCursor, String epoch);
}
