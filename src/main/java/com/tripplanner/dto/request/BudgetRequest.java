package com.tripplanner.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Input DTO for POST /api/v1/trip/features/budget */
@Data
public class BudgetRequest {

    @NotBlank(message = "Destination is required")
    private String destination;

    @Min(value = 1, message = "Minimum 1 day")
    @Max(value = 30, message = "Maximum 30 days")
    private int days;

    @Min(value = 1, message = "Minimum group size is 1")
    private int groupSize;
}
