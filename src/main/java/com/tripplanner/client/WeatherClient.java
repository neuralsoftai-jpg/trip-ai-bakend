package com.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.dto.response.WeatherInfo;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.*;

/**
 * WEATHER CLIENT — OpenWeatherMap 5-Day Forecast API
 *
 * ENDPOINT: GET /data/2.5/forecast?q={city}&cnt=40&units=metric&appid={key}
 *
 * WHY /forecast AND NOT /weather:
 *   /weather gives current conditions only.
 *   /forecast gives 5-day forecast in 3-hour intervals (40 data points).
 *   We group by day and aggregate min/max/rain/description per day.
 *
 * RESPONSE PROCESSING:
 *   OWM returns 40 × 3-hour slots. We group by date and:
 *   - temp_min = minimum of all slots that day
 *   - temp_max = maximum of all slots that day
 *   - description = most common description for that day
 *   - rain = sum of rain.3h for all slots that day
 *   - rainProbability = max pop (probability of precipitation) that day
 *
 * CIRCUIT BREAKER:
 *   If OWM is down, fallback returns "Weather data temporarily unavailable"
 *   with generic entries so dashboard still responds.
 */
@Slf4j
@Component
public class WeatherClient {

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper mapper;

    public WeatherClient(
            @Value("${api.openweathermap.base-url}") String baseUrl,
            @Value("${api.openweathermap.api-key}") String apiKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.mapper = new ObjectMapper();
    }

    @CircuitBreaker(name = "weatherClient", fallbackMethod = "getForecastFallback")
    public List<WeatherInfo> getForecast(String city, int days) {
        try {
            log.info("Fetching weather forecast for: {} ({} days)", city, days);

            int count = Math.min(days * 8, 40);   // 8 slots per day (3h intervals)
            String response = restClient.get()
                    .uri("/forecast?q={city}&cnt={cnt}&units=metric&appid={key}",
                            city, count, apiKey)
                    .retrieve()
                    .body(String.class);

            return parseForecast(response, days);

        } catch (Exception e) {
            log.error("WeatherClient error for {}: {}", city, e.getMessage());
            throw new RuntimeException("Weather fetch failed", e);
        }
    }

    private List<WeatherInfo> parseForecast(String json, int days) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode list = root.get("list");

        // Group 3-hour slots by date
        Map<String, List<JsonNode>> byDay = new LinkedHashMap<>();
        for (JsonNode slot : list) {
            String date = slot.get("dt_txt").asText().substring(0, 10); // "2024-12-25"
            byDay.computeIfAbsent(date, k -> new ArrayList<>()).add(slot);
        }

        List<WeatherInfo> forecast = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, List<JsonNode>> entry : byDay.entrySet()) {
            if (count >= days) break;

            String date = entry.getKey();
            List<JsonNode> slots = entry.getValue();

            double tempMin = slots.stream()
                    .mapToDouble(s -> s.get("main").get("temp_min").asDouble())
                    .min().orElse(25.0);

            double tempMax = slots.stream()
                    .mapToDouble(s -> s.get("main").get("temp_max").asDouble())
                    .max().orElse(35.0);

            double feelsLike = slots.stream()
                    .mapToDouble(s -> s.get("main").get("feels_like").asDouble())
                    .average().orElse(30.0);

            double humidity = slots.stream()
                    .mapToDouble(s -> s.get("main").get("humidity").asDouble())
                    .average().orElse(50.0);

            double windSpeed = slots.stream()
                    .mapToDouble(s -> s.get("wind").get("speed").asDouble())
                    .average().orElse(10.0) * 3.6; // m/s → km/h

            double rainProb = slots.stream()
                    .mapToDouble(s -> s.has("pop") ? s.get("pop").asDouble() : 0.0)
                    .max().orElse(0.0);

            double rainMm = slots.stream()
                    .mapToDouble(s -> s.has("rain") && s.get("rain").has("3h")
                            ? s.get("rain").get("3h").asDouble() : 0.0)
                    .sum();

            // Most common weather description for the day
            String description = slots.get(slots.size() / 2)
                    .get("weather").get(0).get("description").asText();
            String icon = slots.get(slots.size() / 2)
                    .get("weather").get(0).get("icon").asText();

            LocalDate localDate = LocalDate.parse(date);
            String dayOfWeek = localDate.getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            String recommendation = buildRecommendation(rainProb, tempMax, windSpeed);

            forecast.add(WeatherInfo.builder()
                    .date(date)
                    .dayOfWeek(dayOfWeek)
                    .tempMin(Math.round(tempMin * 10.0) / 10.0)
                    .tempMax(Math.round(tempMax * 10.0) / 10.0)
                    .tempFeelsLike(Math.round(feelsLike * 10.0) / 10.0)
                    .description(description)
                    .icon(icon)
                    .rainProbability(Math.round(rainProb * 100.0) / 100.0)
                    .rainMm(Math.round(rainMm * 10.0) / 10.0)
                    .humidity(Math.round(humidity * 10.0) / 10.0)
                    .windSpeedKmh(Math.round(windSpeed * 10.0) / 10.0)
                    .recommendation(recommendation)
                    .build());

            count++;
        }
        return forecast;
    }

    private String buildRecommendation(double rainProb, double tempMax, double windSpeed) {
        List<String> tips = new ArrayList<>();
        if (rainProb > 0.6) tips.add("High rain probability — carry an umbrella");
        else if (rainProb > 0.3) tips.add("Moderate rain chance — keep a raincoat handy");
        if (tempMax > 38) tips.add("Very hot — stay hydrated, avoid outdoor activity 12-4 PM");
        else if (tempMax > 32) tips.add("Warm day — light cotton clothing recommended");
        if (windSpeed > 30) tips.add("Strong winds expected");
        return tips.isEmpty() ? "Pleasant weather — great day to explore" : String.join(". ", tips);
    }

    /** Fallback: returns placeholder entries so dashboard doesn't fail */
    public List<WeatherInfo> getForecastFallback(String city, int days, Throwable t) {
        log.warn("Weather circuit open. Returning fallback. Cause: {}", t.getMessage());
        List<WeatherInfo> fallback = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            fallback.add(WeatherInfo.builder()
                    .date(LocalDate.now().plusDays(i).toString())
                    .dayOfWeek(LocalDate.now().plusDays(i).getDayOfWeek()
                            .getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    .description("Weather data temporarily unavailable")
                    .tempMin(0).tempMax(0).rainProbability(0).rainMm(0)
                    .recommendation("Please check a weather app for up-to-date info")
                    .build());
        }
        return fallback;
    }
}
