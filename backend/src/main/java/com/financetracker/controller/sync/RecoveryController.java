package com.financetracker.controller.sync;

import java.util.UUID;

import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.RecoveryAckDto;
import com.financetracker.model.dto.sync.RecoveryPageDto;
import com.financetracker.model.dto.sync.RecoverySessionDto;
import com.financetracker.model.dto.sync.RecoveryStatusDto;
import com.financetracker.service.sync.RecoverySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public final class RecoveryController implements RecoveryControllerApi {

    private final RecoverySyncService recovery;

    @Override
    public ResponseEntity<RecoveryStatusDto> status(AccessIdentityDto identity) {
        return ResponseEntity.ok(recovery.status(identity));
    }

    @Override
    public ResponseEntity<RecoverySessionDto> start(AccessIdentityDto identity, String epoch) {
        return ResponseEntity.ok(recovery.start(identity, epoch));
    }

    @Override
    public ResponseEntity<RecoveryPageDto> pull(AccessIdentityDto identity, UUID id, long cursor, int limit, String epoch) {
        return ResponseEntity.ok(recovery.pull(identity, id, cursor, limit, epoch));
    }

    @Override
    public ResponseEntity<Void> acknowledge(AccessIdentityDto identity, UUID id, RecoveryAckDto ack, String epoch) {
        recovery.acknowledge(identity, id, ack.lastCursor(), epoch);

        return ResponseEntity.noContent().build();
    }
}
