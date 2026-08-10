package com.tripplanner.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BikeInfo {
    private String ridingRouteDescription;
    private double fuelCostEstimateInr;
    private List<String> restStops;
    private List<String> bikeFriendlyPlaces;
    private List<String> tips;
    private List<String> verifiedSources;
}
