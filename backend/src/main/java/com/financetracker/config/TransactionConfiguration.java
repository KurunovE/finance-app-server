package com.financetracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class TransactionConfiguration {

    @Bean
    @Primary
    TransactionTemplate writeTransaction(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    TransactionTemplate syncReadTransaction(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return transaction;
    }
}
