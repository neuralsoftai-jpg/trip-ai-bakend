package com.tripplanner.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Input DTO for POST /api/v1/trip/features/emergency */
@Data
public class EmergencyRequest {

    @NotBlank(message = "Destination city is required")
    private String destination;

    /**
     * Optional: State name for more accurate hospital/police data.
     * If not provided, we infer from destination lookup.
     */
    private String state;
}
