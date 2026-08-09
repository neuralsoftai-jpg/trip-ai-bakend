package com.tripplanner.exception;

import com.tripplanner.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * GLOBAL EXCEPTION HANDLER
 *
 * WHY @RestControllerAdvice:
 *   Without this, Spring returns HTML error pages for exceptions,
 *   or inconsistent JSON structures. This centralizes all error
 *   handling into one place — the "Single Responsibility" of errors.
 *
 * IMPORTANT: The RateLimitExceededException is handled DIRECTLY in
 * RateLimitConfig (the filter), NOT here. Filters run before Spring MVC,
 * so @RestControllerAdvice cannot catch filter-level exceptions.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid / @Validated DTO validation failures.
     * Returns a map of fieldName → errorMessage for each failed constraint.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(fieldName, message);
        });

        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, "Validation failed: " + errors));
    }

    /**
     * Generic catch-all for unhandled exceptions.
     * Logs full stack trace but returns a safe message to the client.
     *
     * SECURITY NOTE: Never expose stack traces or internal messages
     * to the client. Log internally, return generic message externally.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500,
                        "An internal error occurred. Please try again later."));
    }



    /**
     * Handles IllegalArgumentException — used when an invalid vehicleType
     * or other enum-style value is passed.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, ex.getMessage()));
    }
}
