package com.financetracker.controller.sync;

import java.util.Map;

import com.financetracker.controller.ApiHeaders;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.sync.PullResponseDto;
import com.financetracker.model.dto.sync.PushRequestDto;
import com.financetracker.policy.SyncPolicy;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/v1/sync")
public interface SyncControllerApi {

    @PostMapping("/push")
    ResponseEntity<Map<String, Object>> push(
        @AuthenticationPrincipal AccessIdentityDto identity,
        @Valid @RequestBody PushRequestDto request,
        @RequestHeader(value = ApiHeaders.RECOVERY_EPOCH, required = false) String epoch
    );

    @GetMapping("/pull")
    ResponseEntity<PullResponseDto> pull(
        @AuthenticationPrincipal AccessIdentityDto identity,
        @RequestParam(value = "cursor", defaultValue = SyncPolicy.INITIAL_CURSOR) long cursor,
        @RequestParam(value = "limit", defaultValue = SyncPolicy.DEFAULT_PAGE_SIZE) int limit,
        @RequestHeader(value = ApiHeaders.RECOVERY_EPOCH, required = false) String epoch
    );
}
