package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

/** Fuel cost and per-person split calculation result */
@Data
@Builder
public class FuelSplit {

    private double totalDistanceKm;
    private double fuelPricePerLitre;     // INR
    private double vehicleMileageKmpl;    // km per litre
    private double litresRequired;
    private double totalFuelCostInr;
    private double perPersonCostInr;
    private int groupSize;
    private String vehicleType;
    private boolean electricVehicle;      // If true, fuel cost is 0
    private String note;                  // e.g. "Electric vehicle — no fuel cost"
}
