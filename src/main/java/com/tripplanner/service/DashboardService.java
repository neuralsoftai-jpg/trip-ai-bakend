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

import java.util.ArrayList;
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
        "electric_bike", 0.022,
        "flight",        0.255
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
        boolean isFlight = "flight".equals(vehicleType);
        if (!isFlight && !MILEAGE_MAP.containsKey(vehicleType)) {
            throw new IllegalArgumentException(
                "Invalid vehicle type: '" + req.getVehicleType() +
                "'. Accepted: flight, " + MILEAGE_MAP.keySet());
        }

        // ── 3. FIRE CONCURRENT API CALLS ─────────────────────────────
        boolean routeFeasible = true;
        String feasibilityReason = null;
        double distanceKm = 0;
        String travelTime = "N/A";
        String travelTimeRaw = "0";

        List<String> transportSources = new java.util.concurrent.CopyOnWriteArrayList<>();
        
        CompletableFuture<double[]> routeFuture = null;
        CompletableFuture<String> flightFuture = null;
        CompletableFuture<String> roadSpecificsFuture = null;
        CompletableFuture<String> evSpecificsFuture = null;
        CompletableFuture<String> bikeSpecificsFuture = null;

        if (isFlight) {
            // Direct flight routing
            flightFuture = CompletableFuture.supplyAsync(
                () -> geminiClient.getFlightsInfo(
                        getLocationAddress(req.getSource()),
                        getLocationAddress(req.getDestination()),
                        req.getGroupSize(),
                        transportSources
                ),
                tripExecutor
            );
        } else {
            // Overland car/bike routing via OSRM coordinates
            routeFuture = CompletableFuture.supplyAsync(
                () -> osrmClient.getRoute(
                        req.getSource().getLatitude(),
                        req.getSource().getLongitude(),
                        req.getDestination().getLatitude(),
                        req.getDestination().getLongitude()
                ),
                tripExecutor
            );

            if ("petrol_car".equals(vehicleType) || "diesel_car".equals(vehicleType)) {
                roadSpecificsFuture = CompletableFuture.supplyAsync(
                    () -> geminiClient.getRoadTripSpecifics(
                            getLocationAddress(req.getSource()),
                            getLocationAddress(req.getDestination()),
                            vehicleType,
                            transportSources
                    ),
                    tripExecutor
                );
            } else if ("electric_car".equals(vehicleType)) {
                evSpecificsFuture = CompletableFuture.supplyAsync(
                    () -> geminiClient.getEvInfo(
                            getLocationAddress(req.getSource()),
                            getLocationAddress(req.getDestination()),
                            transportSources
                    ),
                    tripExecutor
                );
            } else if ("bike".equals(vehicleType) || "electric_bike".equals(vehicleType)) {
                bikeSpecificsFuture = CompletableFuture.supplyAsync(
                    () -> geminiClient.getBikeSpecifics(
                            getLocationAddress(req.getSource()),
                            getLocationAddress(req.getDestination()),
                            vehicleType,
                            transportSources
                    ),
                    tripExecutor
                );
            }
        }

        // OpenWeatherMap Forecast (using Destination Coordinates or City)
        double destLat = req.getDestination().getLatitude();
        double destLon = req.getDestination().getLongitude();
        String destCity = req.getDestination().getCity();
        if (destCity == null || destCity.isBlank()) {
            destCity = req.getDestination().getName();
        }
        final String finalDestCity = destCity;
        final double finalDestLat = destLat;
        final double finalDestLon = destLon;

        CompletableFuture<List<WeatherInfo>> weatherFuture = CompletableFuture.supplyAsync(
                () -> (finalDestLat != 0.0 && finalDestLon != 0.0)
                        ? weatherClient.getForecastByCoords(finalDestLat, finalDestLon, req.getDays())
                        : weatherClient.getForecast(finalDestCity, req.getDays()),
                tripExecutor
        );

        // Gemini local Vibe Check
        CompletableFuture<String> vibeFuture = CompletableFuture.supplyAsync(
                () -> geminiClient.getVibeRaw(getLocationAddress(req.getDestination())),
                tripExecutor
        );

        // Gather all running futures
        List<CompletableFuture<?>> futuresList = new ArrayList<>();
        futuresList.add(weatherFuture);
        futuresList.add(vibeFuture);
        if (routeFuture != null) futuresList.add(routeFuture);
        if (flightFuture != null) futuresList.add(flightFuture);
        if (roadSpecificsFuture != null) futuresList.add(roadSpecificsFuture);
        if (evSpecificsFuture != null) futuresList.add(evSpecificsFuture);
        if (bikeSpecificsFuture != null) futuresList.add(bikeSpecificsFuture);

        // Wait for all requests to finish
        try {
            CompletableFuture.allOf(futuresList.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.warn("One or more background tasks failed to execute cleanly: {}", e.getMessage());
        }

        // ── 4. EXTRACT ROUTE DATA ─────────────────────────────────────
        if (routeFuture != null) {
            try {
                double[] routeData = routeFuture.join();
                double distanceMeters = routeData[0];
                double durationSeconds = routeData[1];
                distanceKm = Math.round((distanceMeters / 1000.0) * 10.0) / 10.0;
                travelTime = OsrmClient.formatDuration(durationSeconds);
                travelTimeRaw = String.valueOf((long) durationSeconds);
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof com.tripplanner.exception.RouteInfeasibleException) {
                    log.warn("Overland route infeasible between points: {}", cause.getMessage());
                    routeFeasible = false;
                    feasibilityReason = "Overland road travel is not feasible for this route (e.g. cross-ocean / intercontinental). Recommending flight.";
                } else {
                    log.error("OSRM coordinate routing failed: {}", e.getMessage());
                    // Keep route feasible for domestic distances if fallback returned data
                    if (distanceKm > 3500) {
                        routeFeasible = false;
                        feasibilityReason = "Overland routing service unavailable for this intercontinental route. Recommended mode: Flight.";
                    }
                }
            }
        }

        // ── 5. EXTRACT FLIGHT INFO ────────────────────────────────────
        FlightInfo flightInfo = null;
        if (isFlight || !routeFeasible) {
            String flightJson = null;
            if (isFlight && flightFuture != null) {
                flightJson = flightFuture.join();
            } else {
                // If road trip is infeasible, search for flight fallback
                log.info("Searching flight recommendation since road route is infeasible...");
                try {
                    flightJson = geminiClient.getFlightsInfo(
                            getLocationAddress(req.getSource()),
                            getLocationAddress(req.getDestination()),
                            req.getGroupSize(),
                            transportSources
                    );
                } catch (Exception ex) {
                    log.error("Flight recommendation fallback search failed: {}", ex.getMessage());
                }
            }
            if (flightJson != null) {
                flightInfo = parseFlightResponse(flightJson, new ArrayList<>(transportSources));
            }
            
            // Set air distance using direct Haversine calculations
            distanceKm = Math.round(calculateHaversineKm(
                    req.getSource().getLatitude(), req.getSource().getLongitude(),
                    req.getDestination().getLatitude(), req.getDestination().getLongitude()
            ) * 10.0) / 10.0;

            if (flightInfo != null && flightInfo.getFlights() != null && !flightInfo.getFlights().isEmpty()) {
                travelTime = flightInfo.getFlights().get(0).getDuration();
                travelTimeRaw = "7200"; // approximate default seconds
            }
        }

        // ── 6. EXTRACT WEATHER & VIBE ─────────────────────────────────
        List<WeatherInfo> weather = weatherFuture.join();
        VibeInfo vibe = parseVibeResponse(vibeFuture.join(), getLocationAddress(req.getDestination()));
        String weatherSummary = buildWeatherSummary(weather);

        // ── 7. EXTRACT TRANSPORT SPECIFICS ────────────────────────────
        FuelSplit fuelSplit = null;
        EvInfo evInfo = null;
        RoadTripInfo roadTripInfo = null;
        BikeInfo bikeInfo = null;

        if (isFlight || !routeFeasible) {
            fuelSplit = FuelSplit.builder()
                    .totalDistanceKm(distanceKm)
                    .fuelPricePerLitre(0)
                    .vehicleMileageKmpl(0)
                    .litresRequired(0)
                    .totalFuelCostInr(0)
                    .perPersonCostInr(0)
                    .groupSize(req.getGroupSize())
                    .vehicleType("flight")
                    .electricVehicle(false)
                    .note("Flight mode — N/A for car fuel.")
                    .build();
        } else {
            if ("petrol_car".equals(vehicleType) || "diesel_car".equals(vehicleType)) {
                fuelSplit = calculateFuelSplit(distanceKm, vehicleType, req.getGroupSize());
                if (roadSpecificsFuture != null) {
                    roadTripInfo = parseRoadTripResponse(roadSpecificsFuture.join(), new ArrayList<>(transportSources));
                }
            } else if ("electric_car".equals(vehicleType)) {
                fuelSplit = calculateFuelSplit(distanceKm, vehicleType, req.getGroupSize());
                if (evSpecificsFuture != null) {
                    evInfo = parseEvResponse(evSpecificsFuture.join(), new ArrayList<>(transportSources));
                    if (evInfo != null) {
                        fuelSplit.setPerPersonCostInr(Math.round((evInfo.getTotalChargingCostInr() / req.getGroupSize()) * 100.0) / 100.0);
                        fuelSplit.setTotalFuelCostInr(evInfo.getTotalChargingCostInr());
                    }
                }
            } else if ("bike".equals(vehicleType) || "electric_bike".equals(vehicleType)) {
                fuelSplit = calculateFuelSplit(distanceKm, vehicleType, req.getGroupSize());
                if (bikeSpecificsFuture != null) {
                    bikeInfo = parseBikeResponse(bikeSpecificsFuture.join(), new ArrayList<>(transportSources));
                    if (bikeInfo != null && "electric_bike".equals(vehicleType)) {
                        fuelSplit.setPerPersonCostInr(Math.round((bikeInfo.getFuelCostEstimateInr() / req.getGroupSize()) * 100.0) / 100.0);
                        fuelSplit.setTotalFuelCostInr(bikeInfo.getFuelCostEstimateInr());
                    }
                }
            }
        }

        // ── 8. ASSEMBLE RESPONSE ──────────────────────────────────────
        DashboardResponse response = DashboardResponse.builder()
                .distanceKm(distanceKm)
                .travelTime(travelTime)
                .travelTimeRaw(travelTimeRaw)
                .routeFeasible(routeFeasible)
                .feasibilityReason(feasibilityReason)
                .fuelSplit(fuelSplit)
                .flightInfo(flightInfo)
                .evInfo(evInfo)
                .roadTripInfo(roadTripInfo)
                .bikeInfo(bikeInfo)
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

    private FlightInfo parseFlightResponse(String rawJson, List<String> sources) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            
            JsonNode depNode = node.get("departureAirport");
            FlightInfo.AirportDetail departureAirport = depNode == null ? null : objectMapper.convertValue(depNode, FlightInfo.AirportDetail.class);
            
            JsonNode arrNode = node.get("arrivalAirport");
            FlightInfo.AirportDetail arrivalAirport = arrNode == null ? null : objectMapper.convertValue(arrNode, FlightInfo.AirportDetail.class);
            
            List<FlightInfo.AirportDetail> alternativeAirports = new ArrayList<>();
            if (node.has("alternativeAirports") && node.get("alternativeAirports").isArray()) {
                for (JsonNode alt : node.get("alternativeAirports")) {
                    alternativeAirports.add(objectMapper.convertValue(alt, FlightInfo.AirportDetail.class));
                }
            }
            
            List<FlightInfo.FlightDetail> flights = new ArrayList<>();
            if (node.has("flights") && node.get("flights").isArray()) {
                for (JsonNode fl : node.get("flights")) {
                    flights.add(objectMapper.convertValue(fl, FlightInfo.FlightDetail.class));
                }
            }
            
            List<FlightInfo.TransferOption> airportTransfers = new ArrayList<>();
            if (node.has("airportTransfers") && node.get("airportTransfers").isArray()) {
                for (JsonNode tr : node.get("airportTransfers")) {
                    airportTransfers.add(objectMapper.convertValue(tr, FlightInfo.TransferOption.class));
                }
            }
            
            return FlightInfo.builder()
                    .departureAirport(departureAirport)
                    .arrivalAirport(arrivalAirport)
                    .alternativeAirports(alternativeAirports)
                    .flights(flights)
                    .airportTransfers(airportTransfers)
                    .verifiedSources(sources)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse flight json: {}", e.getMessage());
            return null;
        }
    }

    private EvInfo parseEvResponse(String rawJson, List<String> sources) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            List<EvInfo.ChargingStation> stations = new ArrayList<>();
            if (node.has("chargingStations") && node.get("chargingStations").isArray()) {
                for (JsonNode st : node.get("chargingStations")) {
                    stations.add(objectMapper.convertValue(st, EvInfo.ChargingStation.class));
                }
            }
            List<EvInfo.ChargingStop> stops = new ArrayList<>();
            if (node.has("chargingStops") && node.get("chargingStops").isArray()) {
                for (JsonNode stop : node.get("chargingStops")) {
                    stops.add(objectMapper.convertValue(stop, EvInfo.ChargingStop.class));
                }
            }
            List<String> tips = new ArrayList<>();
            if (node.has("tips") && node.get("tips").isArray()) {
                for (JsonNode tip : node.get("tips")) {
                    tips.add(tip.asText());
                }
            }
            return EvInfo.builder()
                    .chargingStations(stations)
                    .chargingStops(stops)
                    .totalChargingCostInr(node.path("totalChargingCostInr").asDouble(0))
                    .estimatedRangeKm(node.path("estimatedRangeKm").asDouble(320))
                    .tips(tips)
                    .verifiedSources(sources)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse EV json: {}", e.getMessage());
            return null;
        }
    }

    private RoadTripInfo parseRoadTripResponse(String rawJson, List<String> sources) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            List<String> stops = new ArrayList<>();
            if (node.has("fuelStops") && node.get("fuelStops").isArray()) {
                for (JsonNode stop : node.get("fuelStops")) {
                    stops.add(stop.asText());
                }
            }
            List<String> tips = new ArrayList<>();
            if (node.has("tips") && node.get("tips").isArray()) {
                for (JsonNode tip : node.get("tips")) {
                    tips.add(tip.asText());
                }
            }
            return RoadTripInfo.builder()
                    .drivingRouteDescription(node.path("drivingRouteDescription").asText(""))
                    .fuelStops(stops)
                    .tollsEstimateInr(node.path("tollsEstimateInr").asDouble(0))
                    .parkingInfo(node.path("parkingInfo").asText(""))
                    .tips(tips)
                    .verifiedSources(sources)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse road trip json: {}", e.getMessage());
            return null;
        }
    }

    private BikeInfo parseBikeResponse(String rawJson, List<String> sources) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            List<String> stops = new ArrayList<>();
            if (node.has("restStops") && node.get("restStops").isArray()) {
                for (JsonNode stop : node.get("restStops")) {
                    stops.add(stop.asText());
                }
            }
            List<String> places = new ArrayList<>();
            if (node.has("bikeFriendlyPlaces") && node.get("bikeFriendlyPlaces").isArray()) {
                for (JsonNode place : node.get("bikeFriendlyPlaces")) {
                    places.add(place.asText());
                }
            }
            List<String> tips = new ArrayList<>();
            if (node.has("tips") && node.get("tips").isArray()) {
                for (JsonNode tip : node.get("tips")) {
                    tips.add(tip.asText());
                }
            }
            return BikeInfo.builder()
                    .ridingRouteDescription(node.path("ridingRouteDescription").asText(""))
                    .fuelCostEstimateInr(node.path("fuelCostEstimateInr").asDouble(0))
                    .restStops(stops)
                    .bikeFriendlyPlaces(places)
                    .tips(tips)
                    .verifiedSources(sources)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse bike json: {}", e.getMessage());
            return null;
        }
    }

    private double calculateHaversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return R * c;
    }

    private String getLocationAddress(com.tripplanner.dto.ResolvedLocation loc) {
        if (loc == null) return "Unknown";
        if (loc.getFormattedAddress() != null && !loc.getFormattedAddress().isBlank()) {
            return loc.getFormattedAddress();
        }
        if (loc.getName() != null && !loc.getName().isBlank()) {
            return loc.getName();
        }
        if (loc.getCity() != null && !loc.getCity().isBlank()) {
            return loc.getCity();
        }
        return "Unknown";
    }

    private String getLocationKey(com.tripplanner.dto.ResolvedLocation loc) {
        if (loc == null) return "unknown";
        if (loc.getPlaceId() != null && !loc.getPlaceId().isBlank()) {
            return loc.getPlaceId();
        }
        String addr = getLocationAddress(loc);
        return addr.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    private String buildCacheKey(TripRequest req) {
        return String.format("dashboard:%s:%s:%d:%d:%s",
                getLocationKey(req.getSource()),
                getLocationKey(req.getDestination()),
                req.getDays(),
                req.getGroupSize(),
                req.getVehicleType().toLowerCase());
    }

}
