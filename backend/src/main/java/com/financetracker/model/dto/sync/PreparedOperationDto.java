package com.financetracker.model.dto.sync;

import java.util.Map;

import lombok.Builder;

@Builder
public record PreparedOperationDto(
    SyncOperationDto operation,
    Map<String, Object> normalized,
    String hash,
    String error
) {

}
