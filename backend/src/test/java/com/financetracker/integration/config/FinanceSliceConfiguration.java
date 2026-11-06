package com.financetracker.integration.config;

import com.financetracker.service.finance.impl.DefaultFinanceRules;
import com.financetracker.service.finance.impl.DefaultFinanceService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("finance-service-slice")
@Import({DefaultFinanceService.class, DefaultFinanceRules.class})
public class FinanceSliceConfiguration {}
