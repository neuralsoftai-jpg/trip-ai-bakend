package com.tripplanner.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Input DTO for POST /api/v1/trip/features/carbon-footprint */
@Data
public class CarbonRequest {

    @Positive(message = "Distance must be positive")
    private double distanceKm;

    @NotBlank(message = "Vehicle type is required")
    private String vehicleType;

    @Min(value = 1, message = "Group size minimum is 1")
    private int groupSize;
}
