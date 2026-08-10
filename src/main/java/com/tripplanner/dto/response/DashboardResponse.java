package com.tripplanner.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripplanner.dto.ResolvedLocation;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Main response for POST /api/v1/trip/dashboard
 * Aggregates data from OSRM, OpenWeatherMap, Gemini, and internal fuel calculator.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardResponse {

    // ── Route Info (from OSRM / Flight / EV) ─────────────────────────
    private double distanceKm;
    private String travelTime;          // "4 hours 23 minutes"
    private String travelTimeRaw;       // "15780" seconds (raw for calculations)

    // ── Feasibility Engine ───────────────────────────────────────────
    private boolean routeFeasible;
    private String feasibilityReason;

    // ── Mode-Specific Data ───────────────────────────────────────────
    private FuelSplit fuelSplit;
    private FlightInfo flightInfo;
    private EvInfo evInfo;
    private RoadTripInfo roadTripInfo;
    private BikeInfo bikeInfo;

    // ── Weather (from OpenWeatherMap) ────────────────────────────────
    private List<WeatherInfo> weatherForecast;
    private String overallWeatherSummary; // "Partly cloudy, 38°C, low rain"

    // ── Local Vibe (from Gemini) ─────────────────────────────────────
    private VibeInfo localVibe;

    // ── Meta ─────────────────────────────────────────────────────────
    private ResolvedLocation source;
    private ResolvedLocation destination;
    private int days;
    private int groupSize;
    private String vehicleType;
    private boolean cacheHit;           // Useful for debugging cache behavior
}

