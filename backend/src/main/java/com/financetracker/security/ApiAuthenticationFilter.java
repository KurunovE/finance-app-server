package com.financetracker.security;

import java.io.IOException;
import java.util.List;

import com.financetracker.controller.ApiHeaders;
import com.financetracker.controller.advice.ApiErrors;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.policy.AuthPolicy;
import com.financetracker.service.auth.AuthService;
import com.financetracker.service.sync.RecoveryState;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final RateLimits limits;
    private final RecoveryState recovery;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws IOException, ServletException {
        try {
            String path = request.getRequestURI();

            if (path.startsWith("/api/v1/") || path.equals("/v1/me")) {
                var state = recovery.current();
                response.setHeader(ApiHeaders.RECOVERY_EPOCH, state.epoch().toString());

                if (!state.phase().equals(StatusValues.READY)) {
                    throw AuthFailure.builder()
                            .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                            .code(ErrorCodes.RECOVERY_MAINTENANCE)
                            .build();
                }
            }

            if (path.startsWith("/api/v1/") || path.equals("/v1/me")) {
                limits.request(request.getRemoteAddr());
            }

            String header = request.getHeader(ApiHeaders.AUTHORIZATION);

            if (header != null) {
                if (!header.regionMatches(
                        true, 0, AuthPolicy.BEARER_PREFIX, 0, AuthPolicy.BEARER_PREFIX.length())) {
                    throw AuthFailure.unauthorized();
                }

                var identity = auth.authenticate(header.substring(AuthPolicy.BEARER_PREFIX.length()));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                identity,
                                null,
                                List.of(new SimpleGrantedAuthority(SecurityRoles.USER))));
                SecurityContextHolder.setContext(context);
            }

            boolean api = path.startsWith("/api/v1/") || path.equals("/v1/me");

            if (api
                    && (HttpMethod.POST.matches(request.getMethod())
                            || HttpMethod.DELETE.matches(request.getMethod()))) {
                request = new BoundedJsonRequest(request);
            }

            chain.doFilter(request, response);
        } catch (AuthFailure failure) {
            ApiErrors.write(response, failure.status(), failure.getMessage(), failure.retryAfter());
        }
    }
}
