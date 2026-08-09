package com.tripplanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Trip Planner — Spring Boot Entry Point
 *
 * Architecture: Stateless · No Auth · Redis-Cached · Circuit-Broken
 *
 * Every request is self-contained. The app acts as:
 *  - Data Aggregator   → OSRM, OpenWeatherMap
 *  - AI Orchestrator   → Google Gemini
 *  - Calculator        → Fuel cost, Carbon footprint
 *  - Document Builder  → PDF generation (OpenPDF)
 */
@SpringBootApplication
public class TripPlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripPlannerApplication.class, args);
    }
}
