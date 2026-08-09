package com.tripplanner.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * Input DTO for POST /api/v1/trip/dashboard
 *
 * WHY @NotBlank vs @NotNull:
 *   @NotNull rejects null but allows "  " (blank string).
 *   @NotBlank rejects null, empty, and whitespace-only strings.
 *   Always use @NotBlank for String fields.
 */
@Data
public class TripRequest {

    @NotBlank(message = "Source city is required")
    private String source;

    @NotBlank(message = "Destination city is required")
    private String destination;

    @Min(value = 1, message = "Minimum trip duration is 1 day")
    @Max(value = 30, message = "Maximum trip duration is 30 days")
    private int days;

    @Min(value = 1, message = "Minimum group size is 1")
    @Max(value = 50, message = "Maximum group size is 50")
    private int groupSize;

    /**
     * Accepted values: petrol_car, diesel_car, electric_car, bike, electric_bike
     * Validated against enum in service layer for clean error messages.
     */
    @NotBlank(message = "Vehicle type is required")
    private String vehicleType;
}
