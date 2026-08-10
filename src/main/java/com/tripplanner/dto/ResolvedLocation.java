package com.tripplanner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedLocation {
    private String name;
    private String formattedAddress;
    private double latitude;
    private double longitude;
    private String city;
    private String state;
    private String country;
    private String countryCode;
    private String placeId;
}
