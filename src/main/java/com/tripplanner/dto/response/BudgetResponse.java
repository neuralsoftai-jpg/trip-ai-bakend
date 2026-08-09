package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

/** Response for POST /api/v1/trip/features/budget */
@Data
@Builder
public class BudgetResponse {

    private String destination;
    private int days;
    private int groupSize;

    private BudgetTier budgetTier;   // Economy estimate
    private BudgetTier luxuryTier;   // Luxury estimate
    private String aiNote;
    private String disclaimer;       // "Prices are approximate based on 2024 data"

    @Data
    @Builder
    public static class BudgetTier {
        private String tierName;              // "Budget" / "Luxury"
        private double dailyFoodPerPersonInr;
        private double dailyAccommodationInr; // Per room/night
        private double dailyMiscPerPersonInr; // Sightseeing, transport within city
        private double totalForTripInr;       // Grand total for all days & people
        private String accommodationType;     // "Budget Guesthouse / Hostel" or "4-5 Star Hotel"
        private String foodType;              // "Dhabas, local restaurants" or "Fine dining"
    }
}
