package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response for POST /api/v1/trip/features/route-optimize */
@Data
@Builder
public class RouteResponse {

    private String destination;
    private List<String> optimizedSequence;   // Ordered list: ["Start", "Amer Fort", "Nahargarh Fort", ...]
    private List<RouteStep> steps;            // Detailed steps with estimated travel times

    @Data
    @Builder
    public static class RouteStep {
        private int stepNumber;
        private String place;
        private String estimatedTimeFromPrevious;   // "~15 minutes"
        private String tip;                         // "Best visited in morning, less crowd"
    }

    private String algorithmUsed;       // "Nearest Neighbour Heuristic (TSP)"
    private String totalEstimatedTime;  // "Approx 6-7 hours for all places"
    private String optimizationNote;    // Explanation of the TSP algorithm used
}
