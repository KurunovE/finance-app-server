package com.financetracker.service.finance

import com.financetracker.model.enums.Action
import com.financetracker.model.enums.EntityType

interface FinanceRules {
    fun fields(type: EntityType): Set<String>
    fun normalize(type: EntityType, action: Action, data: Map<String, Any?>?): Map<String, Any?>
    fun normalizedName(name: String): String
    fun positiveMoney(value: Any?): Long
}
