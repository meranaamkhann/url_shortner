package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an authenticated user tries to act on a resource they don't own and isn't an admin. */
public class UnauthorizedActionException extends AppException {
    public UnauthorizedActionException(String message) {
        super(message, HttpStatus.FORBIDDEN, "FORBIDDEN_ACTION");
    }
}
