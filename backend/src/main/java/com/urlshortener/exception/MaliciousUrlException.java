package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

public class MaliciousUrlException extends AppException {
    public MaliciousUrlException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "MALICIOUS_URL_REJECTED");
    }
}
