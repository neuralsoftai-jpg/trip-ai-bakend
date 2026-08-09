package com.tripplanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.client.GeminiClient;
import com.tripplanner.dto.request.PackingRequest;
import com.tripplanner.dto.response.PackingListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PackingListService {

    private final GeminiClient geminiClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl.packing:10800}")
    private long cacheTtlSeconds;

    public PackingListResponse getPackingList(PackingRequest req) {
        // ── Cache Check ──────────────────────────────────────────────
        String cacheKey = String.format("packing:%s:%s",
                req.getDestination().toLowerCase().replace(" ", "_"),
                req.getWeatherSummary().toLowerCase().replaceAll("[^a-z0-9]", "_"));

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Cache HIT: {}", cacheKey);
                return objectMapper.convertValue(cached, PackingListResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache GET: {}", e.getMessage());
        }

        // ── Call Gemini ──────────────────────────────────────────────
        String rawJson = geminiClient.getPackingList(
                req.getDestination(), req.getWeatherSummary(), req.getActivities());

        PackingListResponse response = parsePackingList(rawJson, req);

        // ── Cache Result ─────────────────────────────────────────────
        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache SET: {}", e.getMessage());
        }

        return response;
    }


    private PackingListResponse parsePackingList(String rawJson, PackingRequest req) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode categoriesNode = root.get("categories");

            List<PackingListResponse.PackingCategory> categories = new ArrayList<>();
            if (categoriesNode != null && categoriesNode.isArray()) {
                for (JsonNode cat : categoriesNode) {
                    List<String> items = new ArrayList<>();
                    JsonNode itemsNode = cat.get("items");
                    if (itemsNode != null && itemsNode.isArray()) {
                        itemsNode.forEach(item -> items.add(item.asText()));
                    }
                    categories.add(PackingListResponse.PackingCategory.builder()
                            .category(cat.path("category").asText("Miscellaneous"))
                            .items(items)
                            .build());
                }
            }

            return PackingListResponse.builder()
                    .destination(req.getDestination())
                    .weatherSummary(req.getWeatherSummary())
                    .categories(categories)
                    .aiNote(root.path("aiNote").asText("AI-generated packing list"))
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse packing list JSON: {}", e.getMessage());
            // Return minimal response on parse failure
            return PackingListResponse.builder()
                    .destination(req.getDestination())
                    .weatherSummary(req.getWeatherSummary())
                    .categories(List.of(
                            PackingListResponse.PackingCategory.builder()
                                    .category("General")
                                    .items(List.of("Clothes", "Documents", "Phone charger", "Medicines"))
                                    .build()))
                    .aiNote("AI response could not be parsed. Showing basic list.")
                    .build();
        }
    }
}
