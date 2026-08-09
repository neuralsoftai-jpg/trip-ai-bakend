package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

/** Response for POST /api/v1/trip/features/emergency */
@Data
@Builder
public class EmergencyResponse {

    private String destination;
    private String state;

    // National helplines (always same across India)
    private String policeNumber;            // "100"
    private String ambulanceNumber;         // "108"
    private String fireNumber;              // "101"
    private String womenHelplineNumber;     // "1091"
    private String childHelplineNumber;     // "1098"
    private String nationalEmergency;       // "112" (unified)
    private String touristHelpline;         // "1363" (India Tourism)

    // City-specific
    private String nearestHospitalName;
    private String nearestHospitalPhone;
    private String nearestHospitalAddress;
    private String localPoliceStation;
    private String localPolicePhone;

    // Travel-specific
    private String nearestIndianEmbassy;    // For international trips
    private String roadAssistance;          // "1800-180-1520" (NHAI)

    private boolean isFallback;            // True if data from static map (not live API)
    private String dataSource;             // "Static database" / "AI-generated"
}
