package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Local vibe, festivals, and events from Gemini AI */
@Data
@Builder
public class VibeInfo {

    private String destination;
    private String vibeDescription;      // "Jaipur is vibrant, heritage-rich..."
    private String bestTimeToVisit;      // "October to March"
    private List<String> ongoingFestivals; // ["Pushkar Mela", "Diwali Mela"]
    private List<String> localTips;      // ["Bargain at bazaars", "Try Laal Maas"]
    private List<String> mustVisitPlaces; // ["Amer Fort", "Hawa Mahal", "Jantar Mantar"]
    private String safetyRating;         // "Safe for solo travelers"
    private boolean isFallback;          // True if Gemini circuit is open

    // Enriched dynamic fields for Location-Aware Travel Information System
    private List<java.util.Map<String, Object>> foodPlaces;
    private List<java.util.Map<String, Object>> hotelPlaces;
    private List<java.util.Map<String, Object>> activityPlaces;
    private List<java.util.Map<String, Object>> musicPlaces;
    private java.util.Map<String, Object> languageInfo;
    private List<java.util.Map<String, Object>> rulesInfo;
    private List<java.util.Map<String, Object>> placesDetail;
    private java.util.Map<String, Object> bestTimeInfo;
    private List<java.util.Map<String, Object>> marketsInfo;
    private List<java.util.Map<String, Object>> tipsInfo;
}
