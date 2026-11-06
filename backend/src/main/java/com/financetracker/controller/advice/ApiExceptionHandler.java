package com.financetracker.controller.advice;

import java.util.Map;

import com.financetracker.controller.ApiHeaders;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final String DATABASE_FAILURE_LOG = "Database request failed requestId={} type={}";
    private static final String REQUEST_ID_FIELD = "requestId";

    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String, String>> auth(AuthFailure failure) {
        var builder = ResponseEntity.status(failure.status()).header(ApiHeaders.CACHE_CONTROL, ApiHeaders.NO_STORE);

        if (failure.retryAfter() > 0) {
            builder.header(ApiHeaders.RETRY_AFTER, Long.toString(failure.retryAfter()));
        }

        return builder.body(ApiErrors.body(failure.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, String>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiErrors.body(ErrorCodes.INVALID_REQUEST));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> database(DataAccessException failure) {
        var body = ApiErrors.body(ErrorCodes.INTERNAL_ERROR);
        log.error(
                DATABASE_FAILURE_LOG,
                body.get(REQUEST_ID_FIELD),
                failure.getClass().getSimpleName());

        return ResponseEntity.internalServerError().body(body);
    }
}
