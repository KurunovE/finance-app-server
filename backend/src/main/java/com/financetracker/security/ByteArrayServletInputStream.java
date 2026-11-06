package com.financetracker.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

final class ByteArrayServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream input;

    ByteArrayServletInputStream(byte[] body) {
        input = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
        return input.read();
    }

    @Override
    public int read(byte[] bytes, int off, int len) {
        return input.read(bytes, off, len);
    }

    @Override
    public boolean isFinished() {
        return input.available() == 0;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
        try {
            if (!isFinished()) {
                listener.onDataAvailable();
            }

            if (isFinished()) {
                listener.onAllDataRead();
            }
        } catch (IOException failed) {
            listener.onError(failed);
        }
    }
}
