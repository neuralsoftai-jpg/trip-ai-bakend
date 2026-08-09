package com.tripplanner.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Input DTO for POST /api/v1/trip/features/pdf
 * The frontend aggregates all trip data and sends it here for PDF generation.
 * We accept the full dashboard response + optional extras to build the PDF.
 */
@Data
public class PdfRequest {

    @NotBlank(message = "Source is required")
    private String source;

    @NotBlank(message = "Destination is required")
    private String destination;

    @NotNull
    private int days;

    @NotNull
    private int groupSize;

    @NotBlank
    private String vehicleType;

    // Aggregated data from frontend (to avoid re-fetching)
    private String distanceKm;
    private String travelTime;
    private String totalFuelCost;
    private String perPersonCost;
    private String weatherSummary;
    private String localVibe;
    private String festivals;
    private String packingList;
    private String budgetSummary;
    private String emergencyContacts;
    private String carbonFootprint;
    private String optimizedRoute;
}
