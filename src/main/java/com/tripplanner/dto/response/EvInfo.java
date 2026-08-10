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
public class EvInfo {
    private List<ChargingStation> chargingStations;
    private List<ChargingStop> chargingStops;
    private double totalChargingCostInr;
    private double estimatedRangeKm;
    private List<String> tips;
    private List<String> verifiedSources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargingStation {
        private String name;
        private String address;
        private String connectorType;
        private double distance;
        private boolean verified;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargingStop {
        private String location;
        private String duration;
        private double costEstimateInr;
    }
}
