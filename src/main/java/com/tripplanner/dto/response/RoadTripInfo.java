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
public class RoadTripInfo {
    private String drivingRouteDescription;
    private List<String> fuelStops;
    private double tollsEstimateInr;
    private String parkingInfo;
    private List<String> tips;
    private List<String> verifiedSources;
}
