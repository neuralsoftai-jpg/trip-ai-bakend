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
public class FlightInfo {
    private AirportDetail departureAirport;
    private AirportDetail arrivalAirport;
    private List<AirportDetail> alternativeAirports;
    private List<FlightDetail> flights;
    private List<TransferOption> airportTransfers;
    private List<String> verifiedSources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AirportDetail {
        private String name;
        private String code;
        private String city;
        private double distanceFromCityCenterKm;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlightDetail {
        private String airline;
        private String flightNumber;
        private String duration;
        private int stops;
        private String departureTime;
        private String arrivalTime;
        private double pricePerPersonInr;
        private String baggageInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferOption {
        private String mode;
        private double costEstimateInr;
        private String duration;
        private String details;
    }
}
