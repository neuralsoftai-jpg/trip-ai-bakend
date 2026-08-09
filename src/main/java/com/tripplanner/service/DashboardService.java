package com.tripplanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.client.GeminiClient;
import com.tripplanner.client.OsrmClient;
import com.tripplanner.client.WeatherClient;
import com.tripplanner.dto.request.TripRequest;
import com.tripplanner.dto.response.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * DASHBOARD SERVICE — The Heart of the Application
 *
 * ═══════════════════════════════════════════════════════════════════
 * CONCURRENT EXECUTION FLOW:
 *
 *   [Thread: trip-async-1] → OsrmClient.getRoute()        (800ms)
 *   [Thread: trip-async-2] → WeatherClient.getForecast()  (600ms)
 *   [Thread: trip-async-3] → GeminiClient.getVibeRaw()    (1200ms)
 *   [Thread: main]         → FuelCalculator (instant, no I/O)
 *
 *   Total = max(800, 600, 1200) = ~1200ms instead of ~2600ms
 *
 * WHY CompletableFuture.supplyAsync + allOf:
 *   - supplyAsync: Runs task on custom executor (our I/O thread pool)
 *   - allOf: Waits for ALL futures to complete (not just one)
 *   - join(): Gets the result (blocks current thread until done)
 *
 * COMMON MISTAKE: Using .get() instead of .join() in join-all pattern.
 *   .get() throws checked Exception (InterruptedException) requiring try-catch.
 *   .join() throws unchecked CompletionException — cleaner in lambda chains.
 * ═══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class DashboardService {

    private final OsrmClient osrmClient;
    private final WeatherClient weatherClient;
    private final GeminiClient geminiClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor tripExecutor;

    @Value("${cache.ttl.dashboard:10800}")
    private long cacheTtlSeconds;

    @Value("${fuel.price-per-litre:106.0}")
    private double fuelPricePerLitre;

    // Hardcoded maps — match values in application.yml fuel.mileage
    // Using hardcoded Maps instead of @Value("#{${...}}") SpEL injection
    // because SpEL Map injection with underscore/hyphen keys is unreliable
    // across Spring Boot versions. These are static constants — no YAML needed.
    private static final Map<String, Double> MILEAGE_MAP = Map.of(
        "petrol_car",    15.0,
        "diesel_car",    18.0,
        "electric_car",   0.0,
        "bike",          40.0,
        "electric_bike",  0.0
    );

    private static final Map<String, Double> EMISSION_FACTORS = Map.of(
        "petrol_car",    0.192,
        "diesel_car",    0.171,
        "electric_car",  0.053,
        "bike",          0.103,
        "electric_bike", 0.022
    );

    // Explicit constructor required to use @Qualifier on the Executor parameter.
    // @RequiredArgsConstructor (Lombok) cannot carry qualifier metadata.
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardService(OsrmClient osrmClient,
                            WeatherClient weatherClient,
                            GeminiClient geminiClient,
                            RedisTemplate<String, Object> redisTemplate,
                            ObjectMapper objectMapper,
                            @Qualifier("tripExecutor") Executor tripExecutor) {
        this.osrmClient = osrmClient;
        this.weatherClient = weatherClient;
        this.geminiClient = geminiClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tripExecutor = tripExecutor;
    }

    /**
     * Main orchestration method. Redis-first, then parallel external calls.
     */
    public DashboardResponse getDashboard(TripRequest req) {
        // ── 1. CACHE CHECK ────────────────────────────────────────────
        String cacheKey = buildCacheKey(req);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Cache HIT for key: {}", cacheKey);
                DashboardResponse response = objectMapper.convertValue(cached, DashboardResponse.class);
                response.setCacheHit(true);
                return response;
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache GET for key: {}. Cause: {}", cacheKey, e.getMessage());
        }
        log.info("Cache MISS for key: {}", cacheKey);


        // ── 2. VALIDATE VEHICLE TYPE ─────────────────────────────────
        String vehicleType = req.getVehicleType().toLowerCase();
        if (!MILEAGE_MAP.containsKey(vehicleType)) {
            throw new IllegalArgumentException(
                "Invalid vehicle type: '" + req.getVehicleType() +
                "'. Accepted: " + MILEAGE_MAP.keySet());
        }

        // ── 3. FIRE CONCURRENT API CALLS ─────────────────────────────
        //
        // CF1: OSRM — road distance and ETA
        CompletableFuture<double[]> routeFuture = CompletableFuture.supplyAsync(
                () -> osrmClient.getRoute(req.getSource(), req.getDestination()),
                tripExecutor
        );

        // CF2: OpenWeatherMap — day-by-day forecast
        CompletableFuture<List<WeatherInfo>> weatherFuture = CompletableFuture.supplyAsync(
                () -> weatherClient.getForecast(req.getDestination(), req.getDays()),
                tripExecutor
        );

        // CF3: Gemini — local vibe, festivals, tips
        CompletableFuture<String> vibeFuture = CompletableFuture.supplyAsync(
                () -> geminiClient.getVibeRaw(req.getDestination()),
                tripExecutor
        );

        // ── 4. WAIT FOR ALL FUTURES ───────────────────────────────────
        CompletableFuture.allOf(routeFuture, weatherFuture, vibeFuture).join();

        // ── 5. EXTRACT RESULTS ────────────────────────────────────────
        double[] routeData = routeFuture.join();
        double distanceMeters = routeData[0];
        double durationSeconds = routeData[1];
        double distanceKm = Math.round((distanceMeters / 1000.0) * 10.0) / 10.0;

        List<WeatherInfo> weather = weatherFuture.join();

        VibeInfo vibe = parseVibeResponse(vibeFuture.join(), req.getDestination());

        // ── 6. FUEL CALCULATION (Pure Math — Synchronous) ────────────
        FuelSplit fuelSplit = calculateFuelSplit(distanceKm, vehicleType, req.getGroupSize());

        // ── 7. WEATHER SUMMARY ────────────────────────────────────────
        String weatherSummary = buildWeatherSummary(weather);

        // ── 8. ASSEMBLE RESPONSE ──────────────────────────────────────
        DashboardResponse response = DashboardResponse.builder()
                .distanceKm(distanceKm)
                .travelTime(OsrmClient.formatDuration(durationSeconds))
                .travelTimeRaw(String.valueOf((long) durationSeconds))
                .fuelSplit(fuelSplit)
                .weatherForecast(weather)
                .overallWeatherSummary(weatherSummary)
                .localVibe(vibe)
                .source(req.getSource())
                .destination(req.getDestination())
                .days(req.getDays())
                .groupSize(req.getGroupSize())
                .vehicleType(vehicleType)
                .cacheHit(false)
                .build();

        // ── 9. CACHE THE RESULT ───────────────────────────────────────
        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(cacheTtlSeconds));
            log.info("Dashboard response cached with key: {} (TTL={}s)", cacheKey, cacheTtlSeconds);
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache SET for key: {}. Cause: {}", cacheKey, e.getMessage());
        }

        return response;
    }


    // ─────────────────────────────────────────────────────────────────
    // FUEL CALCULATOR
    // ─────────────────────────────────────────────────────────────────

    /**
     * Formula:
     *   litres = distanceKm / mileage
     *   totalCost = litres × pricePerLitre
     *   perPerson = totalCost / groupSize
     *
     * Electric vehicles: fuel cost = 0 (handled via flag)
     */
    private FuelSplit calculateFuelSplit(double distanceKm, String vehicleType, int groupSize) {
        boolean isElectric = vehicleType.contains("electric");
        double mileage = MILEAGE_MAP.getOrDefault(vehicleType, 15.0);

        if (isElectric) {
            return FuelSplit.builder()
                    .totalDistanceKm(distanceKm)
                    .vehicleType(vehicleType)
                    .groupSize(groupSize)
                    .electricVehicle(true)
                    .totalFuelCostInr(0)
                    .perPersonCostInr(0)
                    .note("Electric vehicle — no fuel cost. Estimate charging cost separately.")
                    .build();
        }

        double litresRequired = distanceKm / mileage;
        double totalCost = Math.round(litresRequired * fuelPricePerLitre * 100.0) / 100.0;
        double perPerson = Math.round((totalCost / groupSize) * 100.0) / 100.0;

        return FuelSplit.builder()
                .totalDistanceKm(distanceKm)
                .fuelPricePerLitre(fuelPricePerLitre)
                .vehicleMileageKmpl(mileage)
                .litresRequired(Math.round(litresRequired * 100.0) / 100.0)
                .totalFuelCostInr(totalCost)
                .perPersonCostInr(perPerson)
                .groupSize(groupSize)
                .vehicleType(vehicleType)
                .electricVehicle(false)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // RESPONSE PARSERS
    // ─────────────────────────────────────────────────────────────────

    private VibeInfo parseVibeResponse(String rawJson, String destination) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return VibeInfo.builder()
                    .destination(destination)
                    .vibeDescription(node.path("vibeDescription").asText("No description available"))
                    .bestTimeToVisit(node.path("bestTimeToVisit").asText("October to March"))
                    .ongoingFestivals(parseStringList(node.get("ongoingFestivals")))
                    .localTips(parseStringList(node.get("localTips")))
                    .mustVisitPlaces(parseStringList(node.get("mustVisitPlaces")))
                    .safetyRating(node.path("safetyRating").asText("Please check local advisories"))
                    .foodPlaces(parseMapList(node.get("foodPlaces")))
                    .hotelPlaces(parseMapList(node.get("hotelPlaces")))
                    .activityPlaces(parseMapList(node.get("activityPlaces")))
                    .musicPlaces(parseMapList(node.get("musicPlaces")))
                    .languageInfo(parseMap(node.get("languageInfo")))
                    .rulesInfo(parseMapList(node.get("rulesInfo")))
                    .placesDetail(parseMapList(node.get("placesDetail")))
                    .bestTimeInfo(parseMap(node.get("bestTimeInfo")))
                    .marketsInfo(parseMapList(node.get("marketsInfo")))
                    .tipsInfo(parseMapList(node.get("tipsInfo")))
                    .isFallback(false)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Gemini vibe JSON, using raw text fallback: {}", e.getMessage());
            return VibeInfo.builder()
                    .destination(destination)
                    .vibeDescription(rawJson.length() > 500 ? rawJson.substring(0, 500) : rawJson)
                    .isFallback(true)
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseMapList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        try {
            return objectMapper.convertValue(node,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return objectMapper.convertValue(node,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private String buildWeatherSummary(List<WeatherInfo> weather) {
        if (weather == null || weather.isEmpty()) return "Weather data unavailable";
        double avgMax = weather.stream().mapToDouble(WeatherInfo::getTempMax).average().orElse(0);
        double maxRainProb = weather.stream().mapToDouble(WeatherInfo::getRainProbability).max().orElse(0);
        String rainNote = maxRainProb > 0.5 ? ", rain expected" : "";
        return String.format("Avg. high: %.0f°C%s", avgMax, rainNote);
    }

    private String buildCacheKey(TripRequest req) {
        return String.format("dashboard:%s:%s:%d:%d:%s",
                req.getSource().toLowerCase().replace(" ", "_"),
                req.getDestination().toLowerCase().replace(" ", "_"),
                req.getDays(),
                req.getGroupSize(),
                req.getVehicleType().toLowerCase());
    }
}
