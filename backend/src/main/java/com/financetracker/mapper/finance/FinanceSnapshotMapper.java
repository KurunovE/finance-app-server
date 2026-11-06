package com.financetracker.mapper.finance;

import java.util.Map;

import com.financetracker.model.entity.finance.FinancialEntity;

public interface FinanceSnapshotMapper {
    Map<String, Object> snapshot(FinancialEntity entity);
}
