package com.financetracker.exception;

import java.io.Serial;

import com.financetracker.model.constants.ErrorCodes;
import lombok.Builder;
import org.springframework.http.HttpStatus;

public final class AuthFailure extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final int status;
    private final long retryAfter;

    public AuthFailure(int status, String code) {
        this(status, code, 0);
    }

    @Builder
    public AuthFailure(int status, String code, long retryAfter) {
        super(code);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public long retryAfter() {
        return retryAfter;
    }

    public static AuthFailure unauthorized() {
        return AuthFailure.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .code(ErrorCodes.UNAUTHORIZED)
                .build();
    }
}
