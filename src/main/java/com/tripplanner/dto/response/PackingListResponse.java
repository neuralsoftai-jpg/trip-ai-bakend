package com.tripplanner.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response for POST /api/v1/trip/features/packing-list */
@Data
@Builder
public class PackingListResponse {

    private String destination;
    private String weatherSummary;
    private List<PackingCategory> categories;
    private String aiNote;               // "Based on the weather and your activities..."

    @Data
    @Builder
    public static class PackingCategory {
        private String category;         // "Clothing", "Toiletries", "Documents"
        private List<String> items;      // ["Sunscreen SPF50", "Light cotton kurta"]
    }
}
