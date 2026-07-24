package com.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a custom alias (or, in the rare random-collision case, a generated code) is already taken. */
public class DuplicateAliasException extends AppException {
    public DuplicateAliasException(String alias) {
        super("The alias '" + alias + "' is already in use. Please choose another.", HttpStatus.CONFLICT, "ALIAS_ALREADY_EXISTS");
    }
}
