package com.financetracker.mapper.finance.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import com.financetracker.mapper.finance.FinanceSnapshotMapper;
import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.entity.finance.CategoryBudgetEntity;
import com.financetracker.model.entity.finance.CategoryEntity;
import com.financetracker.model.entity.finance.ExpenseEntity;
import com.financetracker.model.entity.finance.FinancialEntity;
import com.financetracker.model.entity.finance.MonthlyBudgetEntity;
import org.springframework.stereotype.Component;

@Component
public final class DefaultFinanceSnapshotMapper implements FinanceSnapshotMapper {

    @Override
    public Map<String, Object> snapshot(FinancialEntity entity) {
        var map = new LinkedHashMap<String, Object>();

        map.put(JsonFields.ID, entity.id().toString());
        map.put(JsonFields.VERSION, entity.version());
        map.put(JsonFields.CREATED_AT, entity.createdAt().toString());
        map.put(JsonFields.UPDATED_AT, entity.updatedAt().toString());

        switch (entity) {
            case CategoryEntity category -> {
                map.put(JsonFields.NAME, category.name());
                map.put(JsonFields.ARCHIVED, category.archived());
                map.put(JsonFields.SORT_ORDER, category.sortOrder());
            }

            case ExpenseEntity expense -> {
                map.put(JsonFields.AMOUNT_MINOR, expense.amountMinor());
                map.put(JsonFields.CATEGORY_ID, expense.categoryId().toString());
                map.put(JsonFields.OCCURRED_ON, expense.occurredOn().toString());
                map.put(JsonFields.NOTE, expense.note());
            }

            case MonthlyBudgetEntity budget -> {
                map.put(JsonFields.MONTH_START, budget.monthStart().toString());
                map.put(JsonFields.LIMIT_MINOR, budget.limitMinor());
            }

            case CategoryBudgetEntity budget -> {
                map.put(JsonFields.MONTH_START, budget.monthStart().toString());
                map.put(JsonFields.LIMIT_MINOR, budget.limitMinor());
                map.put(JsonFields.CATEGORY_ID, budget.categoryId().toString());
            }
        }

        return map;
    }
}
