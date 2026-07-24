package com.urlshortener.exception;

import com.urlshortener.dto.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

/**
 * Centralised error handling. Every exception, expected or not, ends up here so
 * the API never leaks a raw stack trace or an inconsistent JSON shape to clients.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private String traceId() {
        String id = MDC.get("traceId");
        return id != null ? id : "n/a";
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> handleAppException(AppException ex, HttpServletRequest req) {
        log.warn("Handled application exception [{}] on {}: {}", ex.getErrorCode(), req.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(ex.getStatus().value(), ex.getErrorCode(), ex.getMessage(), req.getRequestURI(), traceId());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.ofValidation(HttpStatus.BAD_REQUEST.value(), "One or more fields are invalid.",
                req.getRequestURI(), fieldErrors, traceId());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), "CONSTRAINT_VIOLATION", ex.getMessage(), req.getRequestURI(), traceId());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Safety net for the race condition where two concurrent requests both pass the
     * "is this alias free?" check and both attempt to insert — the database's unique
     * index is the real source of truth, and this is what catches the loser of that race.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation (likely a uniqueness race) on {}: {}", req.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(HttpStatus.CONFLICT.value(), "CONFLICT",
                "This request conflicts with existing data (e.g. the alias was just taken by another request). Please retry.",
                req.getRequestURI(), traceId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                "You do not have permission to perform this action.", req.getRequestURI(), traceId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED",
                "Authentication is required to access this resource.", req.getRequestURI(), traceId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.METHOD_NOT_ALLOWED.value(), "METHOD_NOT_ALLOWED", ex.getMessage(), req.getRequestURI(), traceId());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(HttpStatus.NOT_FOUND.value(), "ROUTE_NOT_FOUND", "No such endpoint.", req.getRequestURI(), traceId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** Final safety net. Never leak internal exception messages/stack traces to clients. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "An unexpected error occurred. Our team has been notified.", req.getRequestURI(), traceId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
