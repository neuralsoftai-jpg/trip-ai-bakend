package com.tripplanner.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    // ── Route Info (from OSRM) ───────────────────────────────────────
    private double distanceKm;
    private String travelTime;          // "4 hours 23 minutes"
    private String travelTimeRaw;       // "15780" seconds (raw for calculations)

    // ── Fuel & Cost Split ────────────────────────────────────────────
    private FuelSplit fuelSplit;

    // ── Weather (from OpenWeatherMap) ────────────────────────────────
    private List<WeatherInfo> weatherForecast;
    private String overallWeatherSummary; // "Partly cloudy, 38°C, low rain"

    // ── Local Vibe (from Gemini) ─────────────────────────────────────
    private VibeInfo localVibe;

    // ── Meta ─────────────────────────────────────────────────────────
    private String source;
    private String destination;
    private int days;
    private int groupSize;
    private String vehicleType;
    private boolean cacheHit;           // Useful for debugging cache behavior
}
