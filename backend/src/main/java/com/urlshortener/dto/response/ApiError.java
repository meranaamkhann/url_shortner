package com.urlshortener.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Single, consistent error shape returned for every 4xx/5xx response.
 * Consistency here matters a lot more than it sounds — every frontend/mobile
 * client and every monitoring alert rule ends up depending on this shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        List<FieldError> fieldErrors,
        String traceId
) {
    public record FieldError(String field, String message) {}

    public static ApiError of(int status, String errorCode, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, errorCode, message, path, null, traceId);
    }

    public static ApiError ofValidation(int status, String message, String path, List<FieldError> errors, String traceId) {
        return new ApiError(Instant.now(), status, "VALIDATION_FAILED", message, path, errors, traceId);
    }
}
