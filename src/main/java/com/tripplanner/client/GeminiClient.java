package com.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * GEMINI CLIENT — Google Gemini 1.5 Flash API
 *
 * WHY GEMINI FLASH (not Pro):
 *   Flash: ~200ms response, $0.075/1M tokens, lower quality ceiling.
 *   Pro:   ~800ms response, $3.5/1M tokens, higher quality.
 *   For our use cases (vibe, packing, budget), Flash is PERFECTLY sufficient.
 *   Saves 46x on cost. Use Pro only for complex code generation or analysis.
 *
 * API FORMAT:
 *   POST /v1beta/models/gemini-1.5-flash:generateContent?key={apiKey}
 *   Body: { "contents": [{ "parts": [{ "text": "your prompt" }] }] }
 *
 * PROMPT ENGINEERING:
 *   We use structured prompts that ask Gemini to respond in JSON format.
 *   This makes parsing reliable. We use "respond ONLY with valid JSON" and
 *   parse the response. If JSON parsing fails, we fall back to raw text.
 *
 * CIRCUIT BREAKER:
 *   Gemini is a paid API. If it's throttled/down, we return graceful fallbacks
 *   instead of failing. The circuit opens after 60% failure rate (more tolerant
 *   than OSRM because Gemini transient errors are common under load).
 *
 * TOKEN OPTIMIZATION:
 *   We cap max_output_tokens at 1024 for most endpoints.
 *   This controls cost and response time. For packing lists, we use 1500.
 */
@Slf4j
@Component
public class GeminiClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;

    public GeminiClient(
            @Value("${api.gemini.base-url}") String baseUrl,
            @Value("${api.gemini.api-key}") String apiKey,
            @Value("${api.gemini.model:gemini-1.5-flash}") String model) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.mapper = new ObjectMapper();
    }

    /**
     * Gets local vibe, festivals, and events for a destination.
     * Returns structured data for the DashboardResponse.
     */
    @CircuitBreaker(name = "geminiClient", fallbackMethod = "getVibeFallback")
    public String getVibeRaw(String destination) {
        String prompt = """
            You are a local India & international travel expert. For the city/destination: "%s"
            
            Provide real, location-specific travel information. Do NOT fabricate businesses or addresses.
            REQUIREMENTS FOR QUANTITIES:
            - "ongoingFestivals": Must contain at least 3 distinct local festivals or cultural events.
            - "activityPlaces": Must contain at least 4 top local activities and experiences.
            - "rulesInfo": Must contain at least 5 to 6 local rules, etiquette guidelines, and customs.
            - "placesDetail": Must contain at least 5 top places/landmarks to visit with full details.
            - "marketsInfo": Must contain at least 4 top shopping markets, bazaars, or shopping spots.

            Respond with ONLY a valid JSON object (no markdown, no explanation) in this exact format:
            {
              "vibeDescription": "A rich description of the city's atmosphere, culture, and highlights",
              "bestTimeToVisit": "Best months to visit",
              "ongoingFestivals": ["Festival Name 1", "Festival Name 2", "Festival Name 3"],
              "localTips": ["Tip 1", "Tip 2", "Tip 3"],
              "mustVisitPlaces": ["Place 1", "Place 2", "Place 3", "Place 4", "Place 5"],
              "safetyRating": "Safety rating & tip",
              
              "foodPlaces": [
                {
                  "name": "Real Well-known Restaurant/Cafe Name",
                  "rating": 4.5,
                  "priceLevel": "$$ / ₹₹",
                  "distance": "1.2 km away",
                  "cuisine": "Cuisine type",
                  "address": "Real street/area address",
                  "status": "Open / Closed",
                  "phone": "Phone number",
                  "website": "Website URL",
                  "mapsLink": "Google Maps search URL",
                  "verified": true
                }
              ],
              "hotelPlaces": [
                {
                  "name": "Real Hotel Name",
                  "rating": 4.6,
                  "priceRange": "$$$ / ₹₹₹",
                  "distance": "2.1 km away",
                  "amenities": ["Wifi", "AC", "Pool"],
                  "address": "Real street/area address",
                  "mapsLink": "Google Maps URL",
                  "verified": true
                }
              ],
              "activityPlaces": [
                {
                  "name": "Real Activity/Experience Name",
                  "location": "Location name",
                  "rating": 4.7,
                  "price": "Price or Free",
                  "distance": "3.5 km away",
                  "bestTime": "Best time of day",
                  "mapsLink": "Google Maps URL",
                  "verified": true
                }
              ],
              "musicPlaces": [
                {
                  "name": "Real Music Venue/Live Music Spot Name",
                  "rating": 4.4,
                  "location": "Location address",
                  "description": "Short description of music/vibe",
                  "verified": true
                }
              ],
              "languageInfo": {
                "name": "Local language name",
                "phrases": [
                  {
                    "en": "Hello",
                    "local": "Local translation",
                    "pronunciation": "Pronunciation tip",
                    "meaning": "English meaning",
                    "whenToUse": "When to say it"
                  }
                ]
              },
              "rulesInfo": [
                {
                  "title": "Rule name (e.g. Temple Dress Code)",
                  "desc": "Description of rule",
                  "type": "warning",
                  "icon": "🙏",
                  "source": "Source"
                }
              ],
              "placesDetail": [
                {
                  "name": "Real Place Name",
                  "description": "Short description of landmark",
                  "rating": 4.8,
                  "distance": "2.4 km",
                  "hours": "Opening hours",
                  "duration": "Typical visit duration",
                  "bestTime": "Best time of day",
                  "mapsLink": "Google Maps URL",
                  "category": "Historical",
                  "verified": true
                }
              ],
              "bestTimeInfo": {
                "months": ["Oct", "Nov", "Dec", "Jan", "Feb"],
                "dayTimes": [
                  { "time": "8:00 AM – 11:00 AM", "bestFor": "Sightseeing & outdoor walks" },
                  { "time": "4:00 PM – 7:00 PM", "bestFor": "Markets & sunset photography" }
                ],
                "sunrise": "6:15 AM",
                "sunset": "6:30 PM",
                "avgTemp": "22°C"
              },
              "marketsInfo": [
                {
                  "name": "Real Market Name",
                  "rating": 4.5,
                  "type": "Street Market",
                  "whatToBuy": "Traditional items, souvenirs",
                  "bargain": true,
                  "hours": "Opening hours",
                  "icon": "🛒",
                  "verified": true
                }
              ],
              "tipsInfo": [
                {
                  "icon": "💳",
                  "tip": "Tip content",
                  "category": "Money",
                  "color": "blue"
                }
              ]
            }
            """.formatted(destination);

        return callGemini(prompt, 2500);
    }

    /**
     * Generates a smart, activity-linked packing list.
     * Called by PackingListService.
     */
    @CircuitBreaker(name = "geminiClient", fallbackMethod = "getPackingListFallback")
    public String getPackingList(String destination, String weatherSummary, String activities) {
        String prompt = """
            You are a smart travel packing assistant.
            
            Destination: %s
            Weather: %s
            Planned activities: %s
            
            Generate a packing list. Respond with ONLY a valid JSON object:
            {
              "categories": [
                {
                  "category": "Clothing",
                  "items": ["Item 1", "Item 2"]
                },
                {
                  "category": "Toiletries",
                  "items": ["Item 1", "Item 2"]
                },
                {
                  "category": "Documents",
                  "items": ["Aadhaar Card", "Hotel Booking Confirmation"]
                },
                {
                  "category": "Electronics",
                  "items": ["Power bank", "Travel adapter"]
                },
                {
                  "category": "Health & Safety",
                  "items": ["ORS packets", "Sunscreen SPF 50"]
                },
                {
                  "category": "Destination-Specific",
                  "items": ["Activity-specific items"]
                }
              ],
              "aiNote": "Brief note on why these items were chosen"
            }
            """.formatted(destination, weatherSummary,
                activities != null && !activities.isBlank() ? activities : "general sightseeing");

        return callGemini(prompt, 1500);
    }

    /**
     * Generates tier-based budget estimate.
     * Called by BudgetService.
     */
    @CircuitBreaker(name = "geminiClient", fallbackMethod = "getBudgetFallback")
    public String getBudgetEstimate(String destination, int days, int groupSize) {
        String prompt = """
            You are a budget-savvy India travel advisor.
            
            Destination: %s
            Duration: %d days
            Group size: %d people
            
            Provide budget estimates in Indian Rupees (INR). Respond with ONLY valid JSON:
            {
              "budgetTier": {
                "tierName": "Budget",
                "dailyFoodPerPersonInr": 300,
                "dailyAccommodationInr": 500,
                "dailyMiscPerPersonInr": 200,
                "totalForTripInr": 0,
                "accommodationType": "Hostel / Budget guesthouse",
                "foodType": "Dhabas, street food, local restaurants"
              },
              "luxuryTier": {
                "tierName": "Luxury",
                "dailyFoodPerPersonInr": 2000,
                "dailyAccommodationInr": 8000,
                "dailyMiscPerPersonInr": 1500,
                "totalForTripInr": 0,
                "accommodationType": "4-5 Star Hotel",
                "foodType": "Fine dining, hotel restaurants"
              },
              "aiNote": "One line context about prices in this destination",
              "disclaimer": "Approximate prices based on 2024-2025 data"
            }
            
            Calculate totalForTripInr = (dailyFood + dailyMisc) × days × groupSize + accommodation × days
            """.formatted(destination, days, groupSize);

        return callGemini(prompt, 800);
    }

    /**
     * Generates a conversational AI answer for trip planning.
     * Called by ChatService.
     */
    @CircuitBreaker(name = "geminiClient", fallbackMethod = "getChatFallback")
    public String getChatResponse(String destination, String userQuery) {
        String prompt = """
            You are an expert, helpful, and friendly AI Travel Assistant for India trips.
            
            Trip Destination: %s
            User Question: "%s"
            
            Provide a clear, detailed, and accurate answer tailored to the user's question and destination.
            Give specific advice, places to visit, local cuisine, transport tips, or best timings depending on what they asked.
            Format cleanly with bullet points or short paragraphs where helpful.
            Do NOT return JSON code blocks — respond directly in natural markdown conversational text.
            """.formatted(
                destination != null && !destination.isBlank() ? destination : "General Destination",
                userQuery
            );

        return callGemini(prompt, 600);
    }


    // ─────────────────────────────────────────────────────────────────
    // CORE API CALL
    // ─────────────────────────────────────────────────────────────────

    /**
     * Makes the actual HTTP call to Gemini generateContent API.
     *
     * Request structure:
     * {
     *   "contents": [{ "parts": [{ "text": "..." }] }],
     *   "generationConfig": { "maxOutputTokens": N, "temperature": 0.7 }
     * }
     *
     * Temperature 0.7: Balanced between creative and factual.
     * 0.0 = deterministic (good for structured JSON)
     * 1.0 = more creative/random (good for stories)
     * We use 0.3 for JSON responses (more deterministic = valid JSON)
     */
    private String callGemini(String prompt, int maxTokens) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))
                    ),
                    "generationConfig", Map.of(
                            "maxOutputTokens", maxTokens,
                            "temperature", 0.3
                    )
            );

            String response = restClient.post()
                    .uri("/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // Extract text from Gemini response structure:
            // response.candidates[0].content.parts[0].text
            JsonNode root = mapper.readTree(response);
            String rawText = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            // Clean markdown code fences if Gemini wraps JSON in ```json ... ```
            return rawText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            throw new RuntimeException("Gemini API error: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // FALLBACK METHODS
    // ─────────────────────────────────────────────────────────────────

    public String getVibeFallback(String destination, Throwable t) {
        log.warn("Gemini vibe circuit open. Returning fallback for {}. Cause: {}", destination, t.getMessage());
        return """
            {
              "vibeDescription": "AI insights temporarily unavailable. %s is a wonderful destination with rich culture and heritage.",
              "bestTimeToVisit": "October to March (pleasant weather)",
              "ongoingFestivals": ["Check local event calendars for current festivals"],
              "localTips": ["Carry cash for local markets", "Respect local customs", "Stay hydrated"],
              "mustVisitPlaces": ["Local attractions"],
              "safetyRating": "Please check official advisories",
              "foodPlaces": [],
              "hotelPlaces": [],
              "activityPlaces": [],
              "musicPlaces": [],
              "languageInfo": {
                "name": "Local Language",
                "phrases": []
              },
              "rulesInfo": [],
              "placesDetail": [],
              "bestTimeInfo": {
                "months": [],
                "dayTimes": []
              },
              "marketsInfo": [],
              "tipsInfo": []
            }
            """.formatted(destination);
    }

    public String getPackingListFallback(String destination, String weather, String activities, Throwable t) {
        log.warn("Gemini packing circuit open. Returning generic packing list.");
        return """
            {
              "categories": [
                { "category": "Clothing", "items": ["Comfortable walking shoes", "Light clothes", "Jacket/Sweater"] },
                { "category": "Documents", "items": ["Aadhaar Card", "Hotel bookings", "Vehicle RC and insurance"] },
                { "category": "Health", "items": ["Hand sanitizer", "ORS packets", "Basic medicines"] },
                { "category": "Electronics", "items": ["Phone charger", "Power bank", "Earphones"] }
              ],
              "aiNote": "Generic packing list — AI personalization temporarily unavailable"
            }
            """;
    }

    public String getBudgetFallback(String destination, int days, int groupSize, Throwable t) {
        log.warn("Gemini budget circuit open. Returning generic estimate.");
        return """
            {
              "budgetTier": {
                "tierName": "Budget",
                "dailyFoodPerPersonInr": 400, "dailyAccommodationInr": 600,
                "dailyMiscPerPersonInr": 250, "totalForTripInr": %d,
                "accommodationType": "Budget guesthouse", "foodType": "Local dhabas"
              },
              "luxuryTier": {
                "tierName": "Luxury",
                "dailyFoodPerPersonInr": 2500, "dailyAccommodationInr": 9000,
                "dailyMiscPerPersonInr": 2000, "totalForTripInr": %d,
                "accommodationType": "4-5 Star Hotel", "foodType": "Fine dining"
              },
              "aiNote": "Generic estimate — AI personalization temporarily unavailable",
              "disclaimer": "Prices are approximate. Verify locally."
            }
            """.formatted(
                (400 + 250) * days * groupSize + 600 * days,
                (2500 + 2000) * days * groupSize + 9000 * days
        );
    }

    public String getChatFallback(String destination, String userQuery, Throwable t) {
        log.warn("Gemini chat circuit open. Cause: {}", t.getMessage());
        String dest = (destination != null && !destination.isBlank()) ? destination : "your destination";
        String query = (userQuery != null && !userQuery.isBlank()) ? userQuery.toLowerCase() : "";

        // Give context-aware tips even in fallback
        String tip;
        if (query.contains("weather") || query.contains("barish") || query.contains("rain")) {
            tip = "• Check weather.com or IMD (India Met Dept) for real-time " + dest + " forecast.\n• Monsoon in Goa is Jun–Sep; winters (Nov–Feb) are ideal.\n• Carry light rain gear if visiting coastal or hilly areas.";
        } else if (query.contains("food") || query.contains("khana") || query.contains("eat")) {
            tip = "• Try local street food, dhabas, and state specialties.\n• In " + dest + " look for popular local markets and food streets.\n• Always drink bottled/filtered water.";
        } else if (query.contains("hotel") || query.contains("stay") || query.contains("accommodation")) {
            tip = "• Book hotels on MakeMyTrip or Booking.com for best prices.\n• In " + dest + " consider homestays for authentic local experience.\n• Book 2–3 weeks in advance for peak season (Oct–Feb).";
        } else if (query.contains("route") || query.contains("travel") || query.contains("distance")) {
            tip = "• Use Google Maps for real-time traffic and route planning.\n• NH48 and NH66 are major highways for western India travel.\n• Plan for 1 major stop every 3–4 hours of driving.";
        } else {
            tip = "• Carry valid government ID at all times.\n• Download offline maps for " + dest + " before your trip.\n• Keep emergency contacts saved: Police 100, Ambulance 108, Tourist helpline 1800-11-1363.";
        }

        return "🤖 AI Assistant is temporarily unavailable (Gemini API quota limit). Here are helpful tips for your trip to **" + dest + "**:\n\n" + tip + "\n\n_The AI chat will be back shortly. Please try again in a few minutes._";
    }
}

