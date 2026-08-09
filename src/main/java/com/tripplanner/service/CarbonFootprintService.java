package com.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.dto.request.CarbonRequest;
import com.tripplanner.dto.response.CarbonResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * CARBON FOOTPRINT SERVICE
 *
 * FORMULA:
 *   totalCO2 = distanceKm × emissionFactor (kg CO2 per km)
 *   perPersonCO2 = totalCO2 / groupSize
 *
 * EMISSION FACTORS (kg CO2 per km, per vehicle):
 *   Source: IPCC 6th Assessment Report + India grid emission factor
 *   petrol_car:    0.192 kg/km
 *   diesel_car:    0.171 kg/km
 *   electric_car:  0.053 kg/km (India average grid emission factor)
 *   bike:          0.103 kg/km
 *   electric_bike: 0.022 kg/km
 *
 * CO2 GRADE:
 *   < 10 kg  = Low
 *   < 50 kg  = Medium
 *   < 100 kg = High
 *   >= 100kg = Very High
 *
 * CACHE TTL: 6 hours (pure calculation — rarely changes)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarbonFootprintService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // Static emission factors — IPCC values (same as application.yml)
    // Using static Map instead of @Value("#{${...}}") to avoid SpEL injection issues
    private static final Map<String, Double> EMISSION_FACTORS = Map.of(
        "petrol_car",    0.192,
        "diesel_car",    0.171,
        "electric_car",  0.053,
        "bike",          0.103,
        "electric_bike", 0.022
    );

    @Value("${cache.ttl.carbon:21600}")
    private long cacheTtlSeconds;

    public CarbonResponse calculate(CarbonRequest req) {
        String vehicleType = req.getVehicleType().toLowerCase();

        if (!EMISSION_FACTORS.containsKey(vehicleType)) {
            throw new IllegalArgumentException(
                "Invalid vehicle type: '" + req.getVehicleType() +
                "'. Accepted: " + EMISSION_FACTORS.keySet());
        }

        // Round distance to 1 decimal to avoid cache key fragmentation
        double roundedDistance = Math.round(req.getDistanceKm() * 10.0) / 10.0;
        String cacheKey = String.format("carbon:%.1f:%s:%d",
                roundedDistance, vehicleType, req.getGroupSize());

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Cache HIT: {}", cacheKey);
                return objectMapper.convertValue(cached, CarbonResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache GET: {}", e.getMessage());
        }

        double emissionFactor = EMISSION_FACTORS.get(vehicleType);
        double totalCo2 = Math.round(roundedDistance * emissionFactor * 100.0) / 100.0;
        double perPersonCo2 = Math.round((totalCo2 / req.getGroupSize()) * 100.0) / 100.0;

        CarbonResponse response = CarbonResponse.builder()
                .distanceKm(roundedDistance)
                .vehicleType(vehicleType)
                .emissionFactorKgPerKm(emissionFactor)
                .totalCo2Kg(totalCo2)
                .perPersonCo2Kg(perPersonCo2)
                .co2Grade(calculateGrade(totalCo2))
                .comparison(buildComparison(totalCo2))
                .greenAlternative(buildGreenAlternative(vehicleType, roundedDistance, req.getGroupSize()))
                .groupSize(req.getGroupSize())
                .build();

        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache SET: {}", e.getMessage());
        }
        return response;
    }


    private String calculateGrade(double totalCo2) {
        if (totalCo2 < 10) return "Low";
        if (totalCo2 < 50) return "Medium";
        if (totalCo2 < 100) return "High";
        return "Very High";
    }

    private String buildComparison(double totalCo2) {
        double trees = Math.ceil(totalCo2 / 21.0);
        long phoneCharges = Math.round(totalCo2 / 0.005);
        return String.format(
            "Equivalent to planting %.0f trees for a year, or charging a phone %d times",
            trees, phoneCharges);
    }

    private String buildGreenAlternative(String vehicleType, double distanceKm, int groupSize) {
        double busCo2PerPerson = distanceKm * 0.089;
        double carCo2PerPerson = distanceKm * EMISSION_FACTORS.getOrDefault(vehicleType, 0.192) / groupSize;
        double saving = Math.round((1.0 - busCo2PerPerson / carCo2PerPerson) * 100.0);
        if (vehicleType.contains("electric")) {
            return "You're already on a green vehicle. Great choice!";
        }
        if (saving > 0) {
            return String.format("Taking a shared bus would reduce per-person CO2 by ~%.0f%%", saving);
        }
        return "Consider carpooling to reduce per-person emissions";
    }
}
