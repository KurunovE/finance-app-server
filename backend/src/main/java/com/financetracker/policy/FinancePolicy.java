package com.financetracker.policy;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
public class FinancePolicy {
    public static final int MAX_CATEGORY_NAME_LENGTH = 80;
    public static final int MAX_NOTE_LENGTH = 500;
    public static final int FIRST_CALENDAR_YEAR = 1;
    public static final int FIRST_DAY_OF_MONTH = 1;
    public static final int DEFAULT_SORT_ORDER = 0;
    public static final List<String> INITIAL_CATEGORIES = List.of("Продукты", "Транспорт", "Прочее");
    public static final String ISO_DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}";
}
