package com.financetracker.integration.config;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import com.financetracker.controller.auth.AuthController;
import com.financetracker.model.dto.sync.SyncOperationDto;
import com.financetracker.model.enums.Action;
import com.financetracker.model.enums.EntityType;
import com.financetracker.repository.finance.FinanceRepository;
import com.financetracker.service.finance.FinanceRules;
import com.financetracker.service.finance.FinanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = FinanceSliceConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("finance-service-slice")
class ServiceWiringTest {
    @Autowired FinanceService finance;
    @Autowired FinanceRules rules;
    @Autowired ApplicationContext context;
    @MockitoBean FinanceRepository repository;

    @Test
    @DisplayName("Сервис получает зависимости из контекста")
    void serviceUsesInjectedRulesAndRepository() {
        var user = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(repository.find(EntityType.CATEGORY, user, id))
                .thenReturn(null)
                .thenReturn(Map.of("version", 1L, "name", "Food"));
        when(repository.logicalConflict(
                        eq(EntityType.CATEGORY), eq(user), eq(id), anyMap(), eq("food")))
                .thenReturn(null);
        var data = rules.normalize(EntityType.CATEGORY, Action.CREATE, Map.of("name", " Ｆood "));
        var result = finance.apply(user, SyncOperationDto.builder().operationId(UUID.randomUUID()).entityType(EntityType.CATEGORY).entityId(id).action(Action.CREATE).epoch(UUID.randomUUID()).build(), data);
        assertEquals("APPLIED", result.status());
        verify(repository).create(eq(EntityType.CATEGORY), eq(user), eq(id), eq(data), eq("food"));
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(AuthController.class).isEmpty());
    }
}
