package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

public class DuplicateUserException extends AppException {
    public DuplicateUserException(String message) {
        super(message, HttpStatus.CONFLICT, "USER_ALREADY_EXISTS");
    }
}
