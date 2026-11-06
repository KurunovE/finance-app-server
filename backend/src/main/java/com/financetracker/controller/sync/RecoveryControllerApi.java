package com.financetracker.controller.sync;

import java.util.UUID;

import com.financetracker.controller.ApiHeaders;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.RecoveryAckDto;
import com.financetracker.model.dto.sync.RecoveryPageDto;
import com.financetracker.model.dto.sync.RecoverySessionDto;
import com.financetracker.model.dto.sync.RecoveryStatusDto;
import com.financetracker.policy.SyncPolicy;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/sync")
public interface RecoveryControllerApi {

    @GetMapping("/status")
    ResponseEntity<RecoveryStatusDto> status(@AuthenticationPrincipal AccessIdentityDto identity);

    @PostMapping("/recovery/start")
    ResponseEntity<RecoverySessionDto> start(
        @AuthenticationPrincipal AccessIdentityDto identity,
        @RequestHeader(value = ApiHeaders.RECOVERY_EPOCH, required = false) String epoch
    );

    @GetMapping("/recovery/{id}/pull")
    ResponseEntity<RecoveryPageDto> pull(
        @AuthenticationPrincipal AccessIdentityDto identity,
        @PathVariable("id") UUID id,
        @RequestParam(value = "cursor", defaultValue = SyncPolicy.INITIAL_CURSOR) long cursor,
        @RequestParam(value = "limit", defaultValue = SyncPolicy.DEFAULT_PAGE_SIZE) int limit,
        @RequestHeader(value = ApiHeaders.RECOVERY_EPOCH, required = false) String epoch
    );

    @PostMapping("/recovery/{id}/ack")
    ResponseEntity<Void> acknowledge(
        @AuthenticationPrincipal AccessIdentityDto identity,
        @PathVariable("id") UUID id,
        @Valid @RequestBody RecoveryAckDto ack,
        @RequestHeader(value = ApiHeaders.RECOVERY_EPOCH, required = false) String epoch
    );
}
