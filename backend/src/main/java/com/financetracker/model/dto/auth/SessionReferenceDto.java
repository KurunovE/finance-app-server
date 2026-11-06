package com.financetracker.model.dto.auth;

import java.util.UUID;

import lombok.Builder;

@Builder
public record SessionReferenceDto(
    UUID user,
    UUID family
) {

}
