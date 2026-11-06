package com.financetracker.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.financetracker.exception.AuthFailure;
import com.financetracker.model.constants.ErrorCodes;
import com.financetracker.policy.SyncPolicy;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.HttpStatus;

final class BoundedJsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BoundedJsonRequest(HttpServletRequest request) throws IOException {
        super(request);
        body = request.getInputStream().readNBytes(SyncPolicy.MAX_BODY_BYTES + 1);

        if (body.length > SyncPolicy.MAX_BODY_BYTES) {
            throw AuthFailure.builder()
                    .status(HttpStatus.PAYLOAD_TOO_LARGE.value())
                    .code(ErrorCodes.REQUEST_TOO_LARGE)
                    .build();
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
