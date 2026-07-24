package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all application-specific exceptions. Carries an HTTP status
 * and a machine-readable error code so the GlobalExceptionHandler can build a
 * consistent ApiError response without a giant if/else chain.
 */
public abstract class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected AppException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
