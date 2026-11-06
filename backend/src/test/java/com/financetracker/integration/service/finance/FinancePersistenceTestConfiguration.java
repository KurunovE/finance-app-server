package com.financetracker.integration.service.finance;

import javax.sql.DataSource;

import com.financetracker.mapper.finance.impl.DefaultFinanceSnapshotMapper;
import com.financetracker.repository.context.impl.DatabaseUserTransactionContext;
import com.financetracker.repository.finance.impl.JdbcFinanceRepository;
import com.financetracker.service.finance.impl.DefaultFinanceRules;
import com.financetracker.service.finance.impl.DefaultFinanceService;
import com.financetracker.support.PostgresFixture;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("finance-repository-slice")
@Import({
    DefaultFinanceService.class,
    DefaultFinanceRules.class,
    JdbcFinanceRepository.class,
    DatabaseUserTransactionContext.class,
    DefaultFinanceSnapshotMapper.class
})
public class FinancePersistenceTestConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    PostgresFixture financeDatabase() {
        return new PostgresFixture();
    }

    @Bean(initMethod = "migrate")
    Flyway financeTestFlyway(PostgresFixture database) {
        return database.flyway();
    }

    @Bean(destroyMethod = "close")
    @DependsOn("financeTestFlyway")
    HikariDataSource financeDataSource(PostgresFixture database) {
        return database.runtimePool();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    TransactionTemplate transactionTemplate(DataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }
}
