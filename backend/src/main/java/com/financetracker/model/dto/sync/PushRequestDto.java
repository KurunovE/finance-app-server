package com.financetracker.model.dto.sync;

import java.util.List;

import com.financetracker.policy.SyncPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record PushRequestDto(
    @NotNull
    @Size(min = SyncPolicy.MIN_BATCH_SIZE, max = SyncPolicy.MAX_BATCH_SIZE)
    List<@NotNull @Valid SyncOperationDto> operations
) {

}
