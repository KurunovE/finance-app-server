package com.financetracker.service.finance.impl

import com.financetracker.exception.DomainRejection
import com.financetracker.model.constants.ErrorCodes
import com.financetracker.model.constants.JsonFields
import com.financetracker.model.enums.Action
import com.financetracker.model.enums.EntityType
import com.financetracker.policy.FinancePolicy
import com.financetracker.service.finance.FinanceRules
import java.math.BigInteger
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class DefaultFinanceRules : FinanceRules {

    override fun fields(type: EntityType): Set<String> = when (type) {
        EntityType.CATEGORY -> setOf(
            JsonFields.NAME,
            JsonFields.ARCHIVED,
            JsonFields.SORT_ORDER
        )

        EntityType.EXPENSE -> setOf(
            JsonFields.AMOUNT_MINOR,
            JsonFields.CATEGORY_ID,
            JsonFields.OCCURRED_ON,
            JsonFields.NOTE
        )

        EntityType.MONTHLY_BUDGET -> setOf(
            JsonFields.MONTH_START,
            JsonFields.LIMIT_MINOR
        )

        EntityType.CATEGORY_BUDGET -> setOf(
            JsonFields.MONTH_START,
            JsonFields.LIMIT_MINOR,
            JsonFields.CATEGORY_ID
        )
    }

    override fun normalize(type: EntityType, action: Action, data: Map<String, Any?>?): Map<String, Any?> {
        if (action == Action.DELETE) {
            if (!data.isNullOrEmpty()) {
                reject(ErrorCodes.INVALID_PAYLOAD)
            }

            return emptyMap()
        }

        if (data.isNullOrEmpty() || !fields(type).containsAll(data.keys)) {
            reject(ErrorCodes.INVALID_PAYLOAD)
        }

        val result = sortedMapOf<String, Any?>()

        for ((key, value) in data) {
            result[key] = when (key) {
                JsonFields.NAME -> text(
                    Normalizer.normalize(value as? String ?: reject(ErrorCodes.INVALID_NAME), Normalizer.Form.NFKC),
                    FinancePolicy.MAX_CATEGORY_NAME_LENGTH,
                    false
                )

                JsonFields.NOTE -> text(value, FinancePolicy.MAX_NOTE_LENGTH, true)
                JsonFields.AMOUNT_MINOR, JsonFields.LIMIT_MINOR -> positiveMoney(value)
                JsonFields.CATEGORY_ID -> try {
                    UUID.fromString(value as? String ?: reject(ErrorCodes.INVALID_CATEGORY)).toString()
                } catch (invalid: IllegalArgumentException) {
                    reject(ErrorCodes.INVALID_CATEGORY)
                }

                JsonFields.OCCURRED_ON, JsonFields.MONTH_START -> date(value, key == JsonFields.MONTH_START)
                JsonFields.ARCHIVED -> value as? Boolean ?: reject(ErrorCodes.INVALID_ARCHIVED)
                JsonFields.SORT_ORDER -> integer(value).also {
                    if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) {
                        reject(ErrorCodes.INVALID_SORT_ORDER)
                    }
                }.toInt()

                else -> reject(ErrorCodes.INVALID_PAYLOAD)
            }
        }

        if (action == Action.CREATE) {
            val required = fields(type) - setOf(JsonFields.NOTE, JsonFields.ARCHIVED, JsonFields.SORT_ORDER)

            if (!result.keys.containsAll(required)) {
                reject(ErrorCodes.INVALID_PAYLOAD)
            }

            if (type == EntityType.CATEGORY) {
                result.putIfAbsent(JsonFields.ARCHIVED, false)
                result.putIfAbsent(
                    JsonFields.SORT_ORDER,
                    FinancePolicy.DEFAULT_SORT_ORDER
                )
            }

            if (type == EntityType.EXPENSE) {
                result.putIfAbsent(JsonFields.NOTE, null)
            }
        }

        return result
    }

    override fun normalizedName(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    override fun positiveMoney(value: Any?): Long =
        integer(value).also {
            if (it <= 0) {
                reject(ErrorCodes.INVALID_AMOUNT)
            }
        }

    private fun integer(value: Any?): Long = try {
        when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is BigInteger -> value.longValueExact()
            else -> reject(ErrorCodes.INVALID_INTEGER)
        }
    } catch (overflow: ArithmeticException) {
        reject(ErrorCodes.INVALID_INTEGER)
    }

    private fun text(value: Any?, max: Int, nullable: Boolean): String? {
        if (value == null && nullable) {
            return null
        }

        val text = (value as? String ?: reject(ErrorCodes.INVALID_TEXT)).trim()

        if (text.codePointCount(0, text.length) > max || (!nullable && text.isBlank())) {
            reject(ErrorCodes.INVALID_TEXT)
        }

        return if (nullable && text.isEmpty()) {
            null
        } else {
            text
        }
    }

    private fun date(value: Any?, month: Boolean): String {
        val text = value as? String ?: reject(ErrorCodes.INVALID_DATE)

        try {
            if (!Regex(FinancePolicy.ISO_DATE_PATTERN).matches(text)) {
                reject(ErrorCodes.INVALID_DATE)
            }

            val date = LocalDate.parse(text)

            if (date.year < FinancePolicy.FIRST_CALENDAR_YEAR ||
                (month && date.dayOfMonth != FinancePolicy.FIRST_DAY_OF_MONTH)
            ) {
                reject(ErrorCodes.INVALID_DATE)
            }

            return date.toString()
        } catch (invalid: DateTimeParseException) {
            reject(ErrorCodes.INVALID_DATE)
        }
    }

    private fun reject(code: String): Nothing = throw DomainRejection(code)
}
