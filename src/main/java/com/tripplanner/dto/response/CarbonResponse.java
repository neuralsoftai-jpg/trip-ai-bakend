package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

/** Response for POST /api/v1/trip/features/carbon-footprint */
@Data
@Builder
public class CarbonResponse {

    private double distanceKm;
    private String vehicleType;
    private double emissionFactorKgPerKm;     // e.g. 0.192 for petrol car
    private double totalCo2Kg;                // Total CO2 for the trip
    private double perPersonCo2Kg;            // CO2 per person
    private String co2Grade;                  // "Low", "Medium", "High", "Very High"
    private String comparison;                // "Equivalent to planting X trees"
    private String greenAlternative;          // "Taking a bus would emit 60% less CO2"
    private int groupSize;
}
