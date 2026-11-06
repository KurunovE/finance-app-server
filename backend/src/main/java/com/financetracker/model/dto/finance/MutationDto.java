package com.financetracker.model.dto.finance;

import java.util.Map;

import lombok.Builder;

@Builder
public record MutationDto(
    String status,
    Long version,
    Map<String, Object> snapshot,
    String code
) {

}
