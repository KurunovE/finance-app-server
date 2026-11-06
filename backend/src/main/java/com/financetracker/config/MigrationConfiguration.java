package com.financetracker.config;

import com.financetracker.repository.sync.RecoveryRepository;
import com.financetracker.repository.sync.impl.JdbcRecoveryRepository;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {

    @Bean(defaultCandidate = false)
    JdbcTemplate migrationJdbc(Flyway flyway) {
        return new JdbcTemplate(flyway.getConfiguration().getDataSource());
    }

    @Bean(defaultCandidate = false)
    TransactionTemplate migrationTransaction(Flyway flyway) {
        return new TransactionTemplate(
            new DataSourceTransactionManager(flyway.getConfiguration().getDataSource()));
    }

    @Bean(defaultCandidate = false)
    RecoveryRepository migrationRecoveryRepository(
        @Qualifier(BeanNames.MIGRATION_JDBC) JdbcTemplate jdbc) {
        return new JdbcRecoveryRepository(jdbc);
    }
}
