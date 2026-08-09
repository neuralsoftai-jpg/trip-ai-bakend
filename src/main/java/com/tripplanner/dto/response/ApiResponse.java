package com.tripplanner.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Generic API response wrapper for all endpoints.
 *
 * WHY WRAPPER:
 *   Returning raw DTOs means success/error format is inconsistent.
 *   With ApiResponse<T>, the frontend always gets:
 *     { "success": true/false, "data": {...}, "error": "..." }
 *   This is a consistent contract that front-end teams rely on.
 *
 * COMMON MISTAKE: Wrapping EVERYTHING including byte[] (PDF).
 *   Do NOT wrap PDF responses — return ResponseEntity<byte[]> directly.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private String error;
    private int statusCode;
    private Instant timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .statusCode(200)
                .timestamp(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> error(int statusCode, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(message)
                .statusCode(statusCode)
                .timestamp(Instant.now())
                .build();
    }
}
