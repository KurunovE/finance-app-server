package com.financetracker.config;

import java.time.Clock;
import java.util.Map;

import com.financetracker.config.properties.AuthSettings;
import com.financetracker.policy.AuthPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthSettings.class)
public class AuthConfiguration {
    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(
            AuthPolicy.PASSWORD_ENCODER_ID,
            Map.of(
                AuthPolicy.PASSWORD_ENCODER_ID,
                new Argon2PasswordEncoder(
                    AuthPolicy.ARGON_SALT_BYTES,
                    AuthPolicy.ARGON_HASH_BYTES,
                    AuthPolicy.ARGON_PARALLELISM,
                    AuthPolicy.ARGON_MEMORY_KIB,
                    AuthPolicy.ARGON_ITERATIONS)));
    }
}
