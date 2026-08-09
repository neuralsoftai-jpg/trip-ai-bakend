package com.tripplanner.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Input DTO for POST /api/v1/trip/features/route-optimize */
@Data
public class RouteRequest {

    @NotBlank(message = "Destination city is required")
    private String destination;

    /**
     * List of places to visit (e.g., ["Amer Fort", "Hawa Mahal", "City Palace"])
     * The TSP algorithm will return the optimal visiting sequence.
     */
    @NotEmpty(message = "At least 2 places are required for route optimization")
    private List<String> places;
}
