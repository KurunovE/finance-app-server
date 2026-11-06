package com.financetracker.service.finance.impl

import com.financetracker.model.constants.ErrorCodes
import com.financetracker.model.constants.JsonFields
import com.financetracker.model.constants.StatusValues
import com.financetracker.model.dto.finance.MutationDto
import com.financetracker.model.dto.sync.SyncOperationDto
import com.financetracker.model.enums.Action
import com.financetracker.model.enums.EntityType
import com.financetracker.repository.finance.FinanceRepository
import com.financetracker.service.finance.FinanceRules
import com.financetracker.service.finance.FinanceService
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class DefaultFinanceService(
    private val repository: FinanceRepository,
    private val rules: FinanceRules
) : FinanceService {

    override fun apply(user: UUID, operation: SyncOperationDto, payload: Map<String, Any?>): MutationDto {
        val type = operation.entityType()
        val action = operation.action()
        val id = operation.entityId()
        val expected = operation.expectedVersion()

        if ((action == Action.CREATE && expected != null) ||
            (action != Action.CREATE && (expected == null || expected <= 0))
        ) {
            return mutation(StatusValues.VALIDATION_ERROR, null, null, ErrorCodes.INVALID_VERSION)
        }

        val current = repository.find(type, user, id)

        if (action != Action.CREATE && current == null) {
            return mutation(
                StatusValues.NOT_FOUND,
                null,
                null,
                StatusValues.NOT_FOUND
            )
        }

        if (action == Action.CREATE && current != null) {
            return conflict(current, ErrorCodes.ENTITY_EXISTS)
        }

        if (current != null && current[JsonFields.VERSION] != expected) {
            return conflict(
                current,
                ErrorCodes.VERSION_CONFLICT
            )
        }

        if (current != null && current[JsonFields.VERSION] == Long.MAX_VALUE) {
            return mutation(
                StatusValues.VALIDATION_ERROR,
                null,
                null,
                ErrorCodes.VERSION_OVERFLOW
            )
        }

        if (action == Action.DELETE) {
            if (type == EntityType.CATEGORY) {
                return mutation(
                    StatusValues.VALIDATION_ERROR,
                    null,
                    null,
                    ErrorCodes.ARCHIVE_CATEGORY
                )
            }

            check(repository.delete(type, user, id, expected!!) == 1)

            return mutation(StatusValues.APPLIED, Math.addExact(expected, 1), null, null)
        }

        val merged = sortedMapOf<String, Any?>()

        if (current != null) {
            for (key in rules.fields(type)) {
                if (current.containsKey(key)) {
                    merged[key] = current[key]
                }
            }
        }

        merged.putAll(payload)

        if (type == EntityType.EXPENSE || type == EntityType.CATEGORY_BUDGET) {
            val category = repository.find(EntityType.CATEGORY, user, UUID.fromString(merged[JsonFields.CATEGORY_ID] as String)) ?: return mutation(StatusValues.VALIDATION_ERROR, null, null, ErrorCodes.INVALID_CATEGORY)
            val changed = current == null || current[JsonFields.CATEGORY_ID] != merged[JsonFields.CATEGORY_ID]

            if (type == EntityType.EXPENSE && changed && category[JsonFields.ARCHIVED] == true) {
                return mutation(StatusValues.VALIDATION_ERROR, null, null, ErrorCodes.CATEGORY_ARCHIVED)
            }
        }

        val normalized = if (type == EntityType.CATEGORY) {
            rules.normalizedName(merged[JsonFields.NAME] as String)
        } else {
            ""
        }

        val duplicate = repository.logicalConflict(type, user, id, merged, normalized)

        if (duplicate != null) {
            return conflict(duplicate, ErrorCodes.LOGICAL_KEY_CONFLICT)
        }

        if (action == Action.CREATE) {
            repository.create(type, user, id, merged, normalized)
        } else {
            check(repository.update(type, user, id, expected!!, merged, normalized) == 1)
        }

        val snapshot = repository.find(type, user, id)!!

        return mutation(StatusValues.APPLIED, snapshot[JsonFields.VERSION] as Long, snapshot, null)
    }

    private fun conflict(snapshot: Map<String, Any?>, code: String) =
        mutation(StatusValues.CONFLICT, snapshot[JsonFields.VERSION] as Long, snapshot, code)

    private fun mutation(status: String, version: Long?, snapshot: Map<String, Any?>?, code: String?) =
        MutationDto(status, version, snapshot, code)
}
