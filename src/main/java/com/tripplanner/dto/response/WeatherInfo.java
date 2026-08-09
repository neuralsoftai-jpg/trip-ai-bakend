package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

/** Single-day weather forecast from OpenWeatherMap */
@Data
@Builder
public class WeatherInfo {

    private String date;                // "2024-12-25"
    private String dayOfWeek;           // "Wednesday"
    private double tempMin;             // Celsius
    private double tempMax;             // Celsius
    private double tempFeelsLike;
    private String description;         // "broken clouds"
    private String icon;                // OWM icon code e.g. "04d"
    private double rainProbability;     // 0.0 to 1.0 (fraction)
    private double rainMm;              // Total rain in mm for that day
    private double humidity;            // Percentage
    private double windSpeedKmh;
    private String recommendation;      // "Carry an umbrella", "Light clothes advised"
}
