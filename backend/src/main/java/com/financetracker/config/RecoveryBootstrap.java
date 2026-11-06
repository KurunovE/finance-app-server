package com.financetracker.config;

import com.financetracker.repository.sync.RecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@DependsOn(BeanNames.FLYWAY_INITIALIZER)
@RequiredArgsConstructor
public final class RecoveryBootstrap implements InitializingBean {

    @Qualifier(BeanNames.MIGRATION_RECOVERY_REPOSITORY)
    private final RecoveryRepository repository;

    @Qualifier(BeanNames.MIGRATION_TRANSACTION)
    private final TransactionTemplate transaction;

    @Override
    public void afterPropertiesSet() {
        transaction.executeWithoutResult(
            status -> {
                repository.lockExclusive();

                if (repository.current() == null) {
                    repository.prepare();
                }
            });
    }
}
