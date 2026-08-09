package com.tripplanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.client.GeminiClient;
import com.tripplanner.dto.request.BudgetRequest;
import com.tripplanner.dto.response.BudgetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final GeminiClient geminiClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl.budget:10800}")
    private long cacheTtlSeconds;

    public BudgetResponse estimateBudget(BudgetRequest req) {
        String cacheKey = String.format("budget:%s:%d:%d",
                req.getDestination().toLowerCase().replace(" ", "_"),
                req.getDays(),
                req.getGroupSize());

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Cache HIT: {}", cacheKey);
                return objectMapper.convertValue(cached, BudgetResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache GET: {}", e.getMessage());
        }

        String rawJson = geminiClient.getBudgetEstimate(
                req.getDestination(), req.getDays(), req.getGroupSize());

        BudgetResponse response = parseBudgetResponse(rawJson, req);

        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache SET: {}", e.getMessage());
        }
        return response;
    }


    private BudgetResponse parseBudgetResponse(String rawJson, BudgetRequest req) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);

            BudgetResponse.BudgetTier budgetTier = parseTier(root.get("budgetTier"), req);
            BudgetResponse.BudgetTier luxuryTier = parseTier(root.get("luxuryTier"), req);

            return BudgetResponse.builder()
                    .destination(req.getDestination())
                    .days(req.getDays())
                    .groupSize(req.getGroupSize())
                    .budgetTier(budgetTier)
                    .luxuryTier(luxuryTier)
                    .aiNote(root.path("aiNote").asText("AI-generated budget estimate"))
                    .disclaimer(root.path("disclaimer").asText(
                            "Prices are approximate based on 2024-2025 data. Verify locally."))
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse budget JSON: {}", e.getMessage());
            return buildDefaultBudgetResponse(req);
        }
    }

    private BudgetResponse.BudgetTier parseTier(JsonNode node, BudgetRequest req) {
        if (node == null) return null;

        double dailyFood = node.path("dailyFoodPerPersonInr").asDouble(400);
        double dailyAccom = node.path("dailyAccommodationInr").asDouble(600);
        double dailyMisc = node.path("dailyMiscPerPersonInr").asDouble(250);

        // Recalculate total server-side to ensure accuracy
        double totalForTrip = (dailyFood + dailyMisc) * req.getDays() * req.getGroupSize()
                + dailyAccom * req.getDays();

        return BudgetResponse.BudgetTier.builder()
                .tierName(node.path("tierName").asText("Budget"))
                .dailyFoodPerPersonInr(dailyFood)
                .dailyAccommodationInr(dailyAccom)
                .dailyMiscPerPersonInr(dailyMisc)
                .totalForTripInr(Math.round(totalForTrip * 100.0) / 100.0)
                .accommodationType(node.path("accommodationType").asText("Standard accommodation"))
                .foodType(node.path("foodType").asText("Local restaurants"))
                .build();
    }

    private BudgetResponse buildDefaultBudgetResponse(BudgetRequest req) {
        return BudgetResponse.builder()
                .destination(req.getDestination())
                .days(req.getDays())
                .groupSize(req.getGroupSize())
                .budgetTier(BudgetResponse.BudgetTier.builder()
                        .tierName("Budget").dailyFoodPerPersonInr(400)
                        .dailyAccommodationInr(600).dailyMiscPerPersonInr(250)
                        .totalForTripInr((400 + 250.0) * req.getDays() * req.getGroupSize() + 600.0 * req.getDays())
                        .accommodationType("Hostel / Budget guesthouse")
                        .foodType("Dhabas, street food").build())
                .luxuryTier(BudgetResponse.BudgetTier.builder()
                        .tierName("Luxury").dailyFoodPerPersonInr(2500)
                        .dailyAccommodationInr(9000).dailyMiscPerPersonInr(2000)
                        .totalForTripInr((2500 + 2000.0) * req.getDays() * req.getGroupSize() + 9000.0 * req.getDays())
                        .accommodationType("4-5 Star Hotel")
                        .foodType("Fine dining, hotel restaurants").build())
                .aiNote("Generic estimate — AI unavailable")
                .disclaimer("Prices are approximate. Verify locally.")
                .build();
    }
}
