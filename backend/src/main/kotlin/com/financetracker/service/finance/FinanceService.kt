package com.financetracker.service.finance

import com.financetracker.model.dto.finance.MutationDto
import com.financetracker.model.dto.sync.SyncOperationDto
import java.util.UUID

interface FinanceService {
    fun apply(user: UUID, operation: SyncOperationDto, payload: Map<String, Any?>): MutationDto
}
