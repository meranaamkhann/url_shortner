package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

public class UrlExpiredException extends AppException {
    public UrlExpiredException(String shortCode) {
        super("This link (" + shortCode + ") has expired or reached its click limit.", HttpStatus.GONE, "LINK_EXPIRED");
    }
}
