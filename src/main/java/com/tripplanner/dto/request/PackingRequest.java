package com.tripplanner.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Input DTO for POST /api/v1/trip/features/packing-list */
@Data
public class PackingRequest {

    @NotBlank(message = "Destination is required")
    private String destination;

    @NotBlank(message = "Weather summary is required (e.g., 'hot and humid')")
    private String weatherSummary;   // e.g. "Sunny, 38°C, low rain probability"

    private String activities;       // Optional: "temple visits, shopping, heritage walk"
}
