package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

public class LinkPasswordRequiredException extends AppException {
    public LinkPasswordRequiredException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "LINK_PASSWORD_REQUIRED");
    }
}
