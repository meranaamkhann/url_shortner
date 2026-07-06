package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

public class InvalidUrlException extends AppException {
    public InvalidUrlException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "INVALID_URL");
    }
}
