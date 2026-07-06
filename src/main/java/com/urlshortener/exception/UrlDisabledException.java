package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

public class UrlDisabledException extends AppException {
    public UrlDisabledException(String shortCode) {
        super("This link (" + shortCode + ") has been disabled by its owner.", HttpStatus.GONE, "LINK_DISABLED");
    }
}
