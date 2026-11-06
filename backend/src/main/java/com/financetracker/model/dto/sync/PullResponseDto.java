package com.financetracker.model.dto.sync;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.Builder;

@Builder
public record PullResponseDto(
    List<Map<String, Object>> changes,
    long nextCursor,
    boolean hasMore,
    UUID epoch
) {

}
