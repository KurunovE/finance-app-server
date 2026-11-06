package com.financetracker.config;

import com.financetracker.config.properties.AccountMailSettings;
import com.financetracker.policy.MailPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountMailSettings.class)
public class AccountMailConfiguration {
    @Bean(BeanNames.ACCOUNT_MAIL_EXECUTOR)
    ThreadPoolTaskExecutor accountMailExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MailPolicy.CORE_WORKERS);
        executor.setMaxPoolSize(MailPolicy.MAX_WORKERS);
        executor.setQueueCapacity(MailPolicy.QUEUE_CAPACITY);
        executor.setThreadNamePrefix(MailPolicy.THREAD_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(MailPolicy.SHUTDOWN_TIMEOUT_SECONDS);

        return executor;
    }
}
