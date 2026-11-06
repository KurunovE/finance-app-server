package com.financetracker.model.dto.auth;

import java.util.UUID;

import lombok.Builder;

@Builder
public record AccessIdentityDto(
    UUID userId,
    UUID deviceId,
    UUID jti,
    UUID epoch
) {

}
