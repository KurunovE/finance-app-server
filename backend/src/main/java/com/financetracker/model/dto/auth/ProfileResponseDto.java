package com.financetracker.model.dto.auth;

import java.util.UUID;

import lombok.Builder;

@Builder
public record ProfileResponseDto(
    UUID id,
    String email,
    String baseCurrencyCode
) {

}
