package com.financetracker.controller.advice;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import com.financetracker.controller.ApiHeaders;
import com.financetracker.model.constants.JsonFields;
import jakarta.servlet.http.HttpServletResponse;

public final class ApiErrors {

    private static final String MESSAGE = "message";
    private static final String REQUEST_REJECTED = "Request rejected";
    private static final String REQUEST_ID_FIELD = "requestId";
    private static final String UTF_8_CHARSET = "UTF-8";
    private static final String ERROR_BODY_PREFIX = "{\"code\":\"";
    private static final String ERROR_BODY_FIELDS = "\",\"message\":\"Request rejected\",\"requestId\":\"";
    private static final String ERROR_BODY_SUFFIX = "\"}";

    private ApiErrors() {
    }

    public static Map<String, String> body(String code) {
        return Map.of(
            JsonFields.CODE,
            code,
            MESSAGE,
            REQUEST_REJECTED,
            REQUEST_ID_FIELD,
            UUID.randomUUID().toString());
    }

    public static void write(HttpServletResponse response, int status, String code, long retryAfter) throws IOException {
        response.setStatus(status);
        response.setContentType(ApiHeaders.JSON_CONTENT_TYPE);
        response.setCharacterEncoding(UTF_8_CHARSET);
        response.setHeader(ApiHeaders.CACHE_CONTROL, ApiHeaders.NO_STORE);

        if (retryAfter > 0) {
            response.setHeader(ApiHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }

        response.getWriter()
            .write(
                ERROR_BODY_PREFIX
                    + code
                    + ERROR_BODY_FIELDS
                    + UUID.randomUUID()
                    + ERROR_BODY_SUFFIX);
    }
}
