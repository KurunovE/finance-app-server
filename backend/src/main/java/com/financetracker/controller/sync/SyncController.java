package com.financetracker.controller.sync;

import java.util.Map;

import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.PullResponseDto;
import com.financetracker.model.dto.sync.PushRequestDto;
import com.financetracker.service.sync.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public final class SyncController implements SyncControllerApi {

    private final SyncService sync;

    @Override
    public ResponseEntity<Map<String, Object>> push(AccessIdentityDto identity, PushRequestDto request, String epoch) {
        return ResponseEntity.ok(Map.of(JsonFields.RESULTS, sync.push(identity, request.operations(), epoch)));
    }

    @Override
    public ResponseEntity<PullResponseDto> pull(AccessIdentityDto identity, long cursor, int limit, String epoch) {
        return ResponseEntity.ok(sync.pull(identity, cursor, limit, epoch));
    }
}
