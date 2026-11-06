package com.financetracker.config;

import com.financetracker.controller.advice.ApiErrors;
import jakarta.servlet.Filter;
import com.financetracker.model.constants.ErrorCodes;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class HttpSecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("apiAuthenticationFilter") Filter filter,
            Environment environment)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, error) -> {
                                                    String path = request.getRequestURI();
                                                    boolean protectedRoute = path.equals("/api/v1/me") || path.equals("/v1/me") || path.equals("/api/v1/auth/logout") || path.equals("/api/v1/sync/push") || path.equals("/api/v1/sync/pull") || path.equals("/api/v1/sync/status") || path.startsWith("/api/v1/sync/recovery/");
                                                    ApiErrors.write(
                                                            response,
                                                            protectedRoute
                                                                    ? HttpStatus.UNAUTHORIZED
                                                                            .value()
                                                                    : HttpStatus.FORBIDDEN.value(),
                                                            protectedRoute
                                                                    ? ErrorCodes.UNAUTHORIZED
                                                                    : ErrorCodes.FORBIDDEN,
                                                            0);
                                                })
                                        .accessDeniedHandler(
                                                (request, response, error) ->
                                                        ApiErrors.write(
                                                                response,
                                                                HttpStatus.FORBIDDEN.value(),
                                                                ErrorCodes.FORBIDDEN,
                                                                0)))
                .authorizeHttpRequests(
                        requests -> {
                            if (environment.acceptsProfiles(Profiles.of("local & !prod"))) {
                                requests.requestMatchers(
                                                HttpMethod.GET,
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs/swagger-config",
                                                "/api-docs/openapi.yaml")
                                        .permitAll();
                            }

                            requests.requestMatchers(HttpMethod.GET, "/actuator/health")
                                    .permitAll()
                                    .requestMatchers(
                                            HttpMethod.POST,
                                            "/api/v1/auth/register",
                                            "/api/v1/auth/login",
                                            "/api/v1/auth/refresh")
                                    .permitAll()
                                    .requestMatchers(
                                            HttpMethod.POST,
                                            "/api/v1/auth/email/request",
                                            "/api/v1/auth/email/verify",
                                            "/api/v1/auth/password/forgot",
                                            "/api/v1/auth/password/reset")
                                    .permitAll()
                                    .requestMatchers(HttpMethod.GET, "/api/v1/me")
                                    .authenticated()
                                    .requestMatchers(HttpMethod.GET, "/api/v1/sync/pull")
                                    .authenticated()
                                    .requestMatchers(
                                            HttpMethod.GET,
                                            "/api/v1/sync/status",
                                            "/api/v1/sync/recovery/*/pull")
                                    .authenticated()
                                    .requestMatchers(
                                            HttpMethod.POST,
                                            "/api/v1/sync/recovery/start",
                                            "/api/v1/sync/recovery/*/ack")
                                    .authenticated()
                                    .requestMatchers(HttpMethod.POST, "/api/v1/sync/push")
                                    .authenticated()
                                    .requestMatchers(HttpMethod.DELETE, "/v1/me")
                                    .authenticated()
                                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                                    .authenticated()
                                    .anyRequest()
                                    .denyAll();
                        })
                .build();
    }

    @Bean
    FilterRegistrationBean<Filter> apiFilterRegistration(
            @Qualifier("apiAuthenticationFilter") Filter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);

        return registration;
    }
}
