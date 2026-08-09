package com.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OSRM CLIENT — Open Source Routing Machine
 *
 * WHAT OSRM DOES:
 *   Given two lat/lon coordinates, returns:
 *   - Road distance in meters
 *   - Estimated travel duration in seconds
 *
 * API FORMAT:
 *   GET /route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=false
 *
 * HOW WE HANDLE CITY NAMES:
 *   OSRM requires coordinates, not city names. We use OpenWeatherMap's
 *   Geocoding API (same API key) to convert "Jaipur" → {lat, lon}.
 *
 * CIRCUIT BREAKER & FALLBACK:
 *   If OSRM API is slow or times out, fallback dynamically calculates
 *   Haversine straight-line distance × 1.35x Indian road factor.
 */
@Slf4j
@Component
public class OsrmClient {

    private final RestClient restClient;
    private final RestClient geocodingClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    /**
     * Maps ambiguous/state-level Indian place names to their main city / capital.
     * This prevents geocoding "Kashmir" → a village in Rajasthan,
     * or "Uttarakhand" → a forest point instead of Dehradun.
     */
    private static final java.util.Map<String, String> INDIA_CITY_ALIASES = new java.util.HashMap<>();
    static {
        // States → Capitals / Main cities
        INDIA_CITY_ALIASES.put("kashmir",          "Srinagar, Jammu and Kashmir, India");
        INDIA_CITY_ALIASES.put("jammu and kashmir", "Srinagar, Jammu and Kashmir, India");
        INDIA_CITY_ALIASES.put("j&k",              "Srinagar, Jammu and Kashmir, India");
        INDIA_CITY_ALIASES.put("jk",               "Srinagar, Jammu and Kashmir, India");
        INDIA_CITY_ALIASES.put("uttarakhand",       "Dehradun, Uttarakhand, India");
        INDIA_CITY_ALIASES.put("uk",               "Dehradun, Uttarakhand, India");
        INDIA_CITY_ALIASES.put("himachal",          "Shimla, Himachal Pradesh, India");
        INDIA_CITY_ALIASES.put("himachal pradesh",  "Shimla, Himachal Pradesh, India");
        INDIA_CITY_ALIASES.put("hp",               "Shimla, Himachal Pradesh, India");
        INDIA_CITY_ALIASES.put("goa",              "Panaji, Goa, India");
        INDIA_CITY_ALIASES.put("rajasthan",        "Jaipur, Rajasthan, India");
        INDIA_CITY_ALIASES.put("gujarat",          "Ahmedabad, Gujarat, India");
        INDIA_CITY_ALIASES.put("maharashtra",      "Mumbai, Maharashtra, India");
        INDIA_CITY_ALIASES.put("karnataka",        "Bengaluru, Karnataka, India");
        INDIA_CITY_ALIASES.put("kerala",           "Thiruvananthapuram, Kerala, India");
        INDIA_CITY_ALIASES.put("tamilnadu",        "Chennai, Tamil Nadu, India");
        INDIA_CITY_ALIASES.put("tamil nadu",       "Chennai, Tamil Nadu, India");
        INDIA_CITY_ALIASES.put("andhra pradesh",   "Amaravati, Andhra Pradesh, India");
        INDIA_CITY_ALIASES.put("telangana",        "Hyderabad, Telangana, India");
        INDIA_CITY_ALIASES.put("odisha",           "Bhubaneswar, Odisha, India");
        INDIA_CITY_ALIASES.put("orissa",           "Bhubaneswar, Odisha, India");
        INDIA_CITY_ALIASES.put("west bengal",      "Kolkata, West Bengal, India");
        INDIA_CITY_ALIASES.put("bengal",           "Kolkata, West Bengal, India");
        INDIA_CITY_ALIASES.put("bihar",            "Patna, Bihar, India");
        INDIA_CITY_ALIASES.put("jharkhand",        "Ranchi, Jharkhand, India");
        INDIA_CITY_ALIASES.put("chhattisgarh",     "Raipur, Chhattisgarh, India");
        INDIA_CITY_ALIASES.put("mp",               "Bhopal, Madhya Pradesh, India");
        INDIA_CITY_ALIASES.put("madhya pradesh",   "Bhopal, Madhya Pradesh, India");
        INDIA_CITY_ALIASES.put("up",               "Lucknow, Uttar Pradesh, India");
        INDIA_CITY_ALIASES.put("uttar pradesh",    "Lucknow, Uttar Pradesh, India");
        INDIA_CITY_ALIASES.put("punjab",           "Chandigarh, Punjab, India");
        INDIA_CITY_ALIASES.put("haryana",          "Chandigarh, Haryana, India");
        INDIA_CITY_ALIASES.put("assam",            "Guwahati, Assam, India");
        INDIA_CITY_ALIASES.put("northeast",        "Guwahati, Assam, India");
        INDIA_CITY_ALIASES.put("manipur",          "Imphal, Manipur, India");
        INDIA_CITY_ALIASES.put("meghalaya",        "Shillong, Meghalaya, India");
        INDIA_CITY_ALIASES.put("sikkim",           "Gangtok, Sikkim, India");
        INDIA_CITY_ALIASES.put("nagaland",         "Kohima, Nagaland, India");
        INDIA_CITY_ALIASES.put("tripura",          "Agartala, Tripura, India");
        INDIA_CITY_ALIASES.put("leh",              "Leh, Ladakh, India");
        INDIA_CITY_ALIASES.put("ladakh",           "Leh, Ladakh, India");
        INDIA_CITY_ALIASES.put("delhi",            "New Delhi, India");
        INDIA_CITY_ALIASES.put("new delhi",        "New Delhi, India");
    }

    public OsrmClient(
            @Value("${api.osrm.base-url}") String osrmBaseUrl,
            @Value("${api.openweathermap.api-key}") String apiKey) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(15000);

        this.restClient = RestClient.builder()
                .baseUrl(osrmBaseUrl)
                .requestFactory(factory)
                .build();

        this.geocodingClient = RestClient.builder()
                .baseUrl("https://api.openweathermap.org/geo/1.0")
                .requestFactory(factory)
                .build();

        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns [distanceMeters, durationSeconds] as a double array.
     * Index 0 = distance in meters
     * Index 1 = duration in seconds
     */
    @CircuitBreaker(name = "osrmClient", fallbackMethod = "getRouteFallback")
    public double[] getRoute(String sourceCity, String destinationCity) {
        try {
            // Sanitize city names: remove non-ASCII chars (arrows, emojis, etc.) that corrupt geocoding
            String cleanSource = sourceCity.replaceAll("[^\\x00-\\x7F]", "").trim();
            String cleanDest = destinationCity.replaceAll("[^\\x00-\\x7F]", "").trim();
            log.info("Fetching OSRM route: {} → {} (sanitized from: {} → {})",
                    cleanSource, cleanDest, sourceCity, destinationCity);

            double[] sourceCords = geocodeCity(cleanSource);
            double[] destCords = geocodeCity(cleanDest);

            String path = String.format("/route/v1/driving/%f,%f;%f,%f?overview=false",
                    sourceCords[1], sourceCords[0],   // lon, lat
                    destCords[1], destCords[0]);

            String response = restClient.get()
                    .uri(path)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode route = root.get("routes").get(0);

            double distance = route.get("distance").asDouble();   // meters
            double duration = route.get("duration").asDouble();   // seconds

            log.info("OSRM Success: {} → {} = {}km, {}s", cleanSource, cleanDest,
                    Math.round(distance / 100.0) / 10.0, (long) duration);

            return new double[]{distance, duration};

        } catch (Exception e) {
            log.error("OSRM API error: {}", e.getMessage());
            throw new RuntimeException("OSRM routing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Dynamic Fallback when OSRM circuit is open or call fails.
     * Calculates exact Haversine distance × 1.35x Indian road terrain factor.
     */
    public double[] getRouteFallback(String sourceCity, String destinationCity, Throwable t) {
        log.warn("OSRM routing fallback triggered for '{}' -> '{}'. Cause: {}", sourceCity, destinationCity, t.getMessage());
        try {
            String cleanSource = sourceCity.replaceAll("[^\\x00-\\x7F]", "").trim();
            String cleanDest = destinationCity.replaceAll("[^\\x00-\\x7F]", "").trim();

            double[] srcCords = geocodeCity(cleanSource);
            double[] dstCords = geocodeCity(cleanDest);

            double haversineKm = calculateHaversineKm(srcCords[0], srcCords[1], dstCords[0], dstCords[1]);
            // Indian road terrain factor (approx 1.35x driving distance vs straight line)
            double roadDistanceMeters = haversineKm * 1.35 * 1000.0;
            // Estimated average highway/terrain driving speed in India ~ 50 km/h
            double durationSeconds = ((haversineKm * 1.35) / 50.0) * 3600.0;

            log.info("Dynamic Haversine Fallback Route: {} -> {} = {} km, {} hrs",
                    cleanSource, cleanDest, Math.round(roadDistanceMeters / 1000.0), Math.round(durationSeconds / 3600.0));

            return new double[]{roadDistanceMeters, durationSeconds};
        } catch (Exception e) {
            log.error("Geocoding/Haversine fallback failed: {}", e.getMessage());
            return new double[]{500_000.0, 32_400.0};
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

    /**
     * Converts city name to [latitude, longitude] using OWM Geocoding API.
     *
     * DISAMBIGUATION STRATEGY:
     *  1. Check INDIA_CITY_ALIASES: resolves state names / ambiguous names to main city
     *     (e.g. "kashmir" → "Srinagar, Jammu and Kashmir, India")
     *  2. Search with ",India" suffix for precision
     *  3. Validate result is within India bounding box (lat 6-37, lon 68-97)
     */
    private double[] geocodeCity(String city) {
        try {
            // Strip non-ASCII/special chars that corrupt query
            String sanitized = city.replaceAll("[^a-zA-Z0-9 \\-]", "").trim();

            // ── Step 1: Alias Resolution ──────────────────────────────────
            // Check if city is a state name or commonly ambiguous term
            String aliasKey = sanitized.toLowerCase().trim();
            String resolvedQuery;
            if (INDIA_CITY_ALIASES.containsKey(aliasKey)) {
                resolvedQuery = INDIA_CITY_ALIASES.get(aliasKey);
                log.info("Alias resolved: '{}' → '{}'", sanitized, resolvedQuery);
            } else {
                resolvedQuery = sanitized + ", India";
            }

            // ── Step 2: Primary Geocoding with resolved query ──────────────
            String response = geocodingClient.get()
                    .uri("/direct?q={city}&limit=5&appid={key}", resolvedQuery, apiKey)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);

            if (!root.isEmpty()) {
                // Prefer results where country = IN and within India bounding box
                for (JsonNode node : root) {
                    String country = node.path("country").asText("");
                    double lat = node.path("lat").asDouble();
                    double lon = node.path("lon").asDouble();
                    // India bounding box: lat 6-37, lon 68-97
                    boolean inIndiaBox = lat >= 6 && lat <= 37 && lon >= 68 && lon <= 97;
                    if ("IN".equalsIgnoreCase(country) && inIndiaBox) {
                        log.info("Geocoded '{}' → lat={}, lon={} (IN, bbox OK)", sanitized, lat, lon);
                        return new double[]{lat, lon};
                    }
                }
                // Accept IN country even if slightly outside standard bbox (J&K goes up to 37+)
                for (JsonNode node : root) {
                    String country = node.path("country").asText("");
                    if ("IN".equalsIgnoreCase(country)) {
                        double lat = node.path("lat").asDouble();
                        double lon = node.path("lon").asDouble();
                        log.info("Geocoded '{}' → lat={}, lon={} (IN, relaxed bbox)", sanitized, lat, lon);
                        return new double[]{lat, lon};
                    }
                }
            }

            // ── Step 3: Fallback without ",India" suffix ───────────────────
            String queryIN = sanitized + ",IN";
            response = geocodingClient.get()
                    .uri("/direct?q={city}&limit=5&appid={key}", queryIN, apiKey)
                    .retrieve()
                    .body(String.class);
            root = objectMapper.readTree(response);

            if (!root.isEmpty()) {
                for (JsonNode node : root) {
                    double lat = node.path("lat").asDouble();
                    double lon = node.path("lon").asDouble();
                    String country = node.path("country").asText("");
                    boolean inIndiaBox = lat >= 6 && lat <= 38 && lon >= 68 && lon <= 98;
                    if ("IN".equalsIgnoreCase(country) && inIndiaBox) {
                        log.info("Geocoded (fallback) '{}' → lat={}, lon={}", sanitized, lat, lon);
                        return new double[]{lat, lon};
                    }
                }
                double lat = root.get(0).get("lat").asDouble();
                double lon = root.get(0).get("lon").asDouble();
                log.warn("Geocoded '{}' → lat={}, lon={} (last resort, may be incorrect)", sanitized, lat, lon);
                return new double[]{lat, lon};
            }

            throw new IllegalArgumentException("City not found: " + sanitized);

        } catch (Exception e) {
            log.error("Geocoding failed for city '{}': {}", city, e.getMessage());
            throw new RuntimeException("Failed to geocode city: " + city, e);
        }
    }

    /**
     * Formats duration seconds into human-readable string.
     * e.g. 15780 → "4 hours 23 minutes"
     */
    public static String formatDuration(double seconds) {
        long totalMinutes = (long) (seconds / 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours == 0) return minutes + " minutes";
        if (minutes == 0) return hours + " hours";
        return hours + " hours " + minutes + " minutes";
    }
}
