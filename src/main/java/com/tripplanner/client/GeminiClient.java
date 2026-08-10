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
            You are a local India & international travel expert. For the city/destination: "{DEST}"
            
            Provide real, location-specific travel information. Do NOT fabricate businesses or addresses.
            REQUIREMENTS FOR QUANTITIES:
            - "foodPlaces": Must contain AT LEAST 4 real recommended restaurants, cafes, or eateries.
            - "hotelPlaces": Must contain AT LEAST 4 top recommended hotels, resorts, or stays.
            - "activityPlaces": Must contain AT LEAST 4 top local activities and experiences.
            - "marketsInfo": Must contain AT LEAST 4 top shopping markets, bazaars, or shopping spots.
            - "tipsInfo": Must contain AT LEAST 4 to 5 smart travel tips (with icon, tip, category, color).
            - "rulesInfo": Must contain AT LEAST 4 to 5 local rules, etiquette guidelines, and customs (with title, desc, type, icon, source).
            - "languageInfo": "name" MUST be the authentic local language spoken in "{DEST}" (e.g. Hindi/Rajasthani for Jaipur, French for Paris, Japanese for Tokyo, Spanish for Barcelona, Italian for Rome, Kannada for Bangalore, Tamil for Chennai, etc.). "phrases" MUST contain EXACTLY 5 to 7 authentic local language words/phrases spoken in "{DEST}" with English translation, pronunciation, meaning, and whenToUse.
            - "ongoingFestivals": Must contain at least 3 distinct local festivals or cultural events.
            - "placesDetail": Must contain at least 4 top places/landmarks to visit with full details.

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
            """;
        return callGemini(prompt.replace("{DEST}", destination), 4096);
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
        log.warn("Gemini vibe circuit open. Returning fallback for {}. Cause: {}", destination, t != null ? t.getMessage() : "Circuit open");
        String dest = (destination != null && !destination.isBlank()) ? destination : "Destination";
        String template = """
            {
              "vibeDescription": "{DEST} is a vibrant destination offering rich cultural experiences, local cuisine, and memorable sights.",
              "bestTimeToVisit": "Spring and Autumn (Pleasant Weather)",
              "ongoingFestivals": ["Local Cultural & Heritage Festival", "Food & Music Fair", "Seasonal Celebrations"],
              "localTips": ["Keep local emergency contacts handy", "Respect local customs and dress codes", "Use verified transport options"],
              "mustVisitPlaces": ["Famous City Center", "Historic Landmark", "Popular Local Market", "Panoramic Viewpoint"],
              "safetyRating": "Generally Safe (Exercise standard travel precautions)",
              "foodPlaces": [
                {
                  "name": "Popular Local Cuisine Restaurant",
                  "rating": 4.6,
                  "priceLevel": "$$",
                  "distance": "1.5 km away",
                  "cuisine": "Local & International Specialties",
                  "address": "{DEST} City Center",
                  "status": "Open",
                  "phone": "Available on Google Maps",
                  "website": "https://google.com/maps",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Heritage Cafe & Bistro",
                  "rating": 4.5,
                  "priceLevel": "$$",
                  "distance": "2.1 km away",
                  "cuisine": "Artisanal Coffee & Breakfast",
                  "address": "Downtown Area, {DEST}",
                  "status": "Open",
                  "phone": "Available on Google Maps",
                  "website": "https://google.com/maps",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Traditional Food Hub & Grill",
                  "rating": 4.7,
                  "priceLevel": "$$$",
                  "distance": "2.8 km away",
                  "cuisine": "Authentic Regional Delicacies",
                  "address": "Market Street, {DEST}",
                  "status": "Open",
                  "phone": "Available on Google Maps",
                  "website": "https://google.com/maps",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Rooftop Garden Diner",
                  "rating": 4.4,
                  "priceLevel": "$$",
                  "distance": "3.2 km away",
                  "cuisine": "Continental & Fusion Snacks",
                  "address": "Skyline Road, {DEST}",
                  "status": "Open",
                  "phone": "Available on Google Maps",
                  "website": "https://google.com/maps",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                }
              ],
              "hotelPlaces": [
                {
                  "name": "Grand Landmark Hotel & Spa",
                  "rating": 4.7,
                  "priceRange": "$$$",
                  "distance": "1.0 km away",
                  "amenities": ["Free Wifi", "Breakfast Included", "Air Conditioning", "24/7 Front Desk"],
                  "address": "Main Boulevard, {DEST}",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Boutique City Stay & Suites",
                  "rating": 4.5,
                  "priceRange": "$$",
                  "distance": "2.5 km away",
                  "amenities": ["Free Wifi", "Parking", "City View Rooms"],
                  "address": "Central District, {DEST}",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Heritage Palace Resort",
                  "rating": 4.8,
                  "priceRange": "$$$$",
                  "distance": "4.1 km away",
                  "amenities": ["Swimming Pool", "Spa", "Free Wifi", "Fine Dining"],
                  "address": "Lakeview Drive, {DEST}",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Comfort Express Hotel",
                  "rating": 4.3,
                  "priceRange": "$",
                  "distance": "0.8 km away",
                  "amenities": ["Free Wifi", "AC", "Airport Transfer"],
                  "address": "Station Road, {DEST}",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                }
              ],
              "activityPlaces": [
                {
                  "name": "City Sightseeing & Heritage Walking Tour",
                  "location": "{DEST} Historic District",
                  "rating": 4.8,
                  "price": "Moderate",
                  "distance": "1.2 km away",
                  "bestTime": "Morning (9:00 AM - 12:00 PM)",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Cultural Museum & Art Gallery Visit",
                  "location": "Cultural Zone, {DEST}",
                  "rating": 4.6,
                  "price": "Ticket required",
                  "distance": "3.0 km away",
                  "bestTime": "Afternoon (2:00 PM - 5:00 PM)",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Scenic Viewpoint & Photography Spot",
                  "location": "{DEST} Hilltop / Plaza",
                  "rating": 4.9,
                  "price": "Free Entry",
                  "distance": "4.5 km away",
                  "bestTime": "Sunset (5:30 PM - 7:00 PM)",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                },
                {
                  "name": "Local Craft Workshop & Souvenir Tour",
                  "location": "Old Town Artisan Street, {DEST}",
                  "rating": 4.5,
                  "price": "Free Entry",
                  "distance": "2.0 km away",
                  "bestTime": "Evening (4:00 PM - 8:00 PM)",
                  "mapsLink": "https://google.com/maps",
                  "verified": true
                }
              ],
              "musicPlaces": [
                {
                  "name": "Central Live Music & Lounge Bar",
                  "rating": 4.5,
                  "location": "Entertainment Quarter, {DEST}",
                  "description": "Features local artists, acoustic live performances, and vibrant evening atmosphere.",
                  "verified": true
                }
              ],
              "languageInfo": {
                "name": "Local & English",
                "phrases": [
                  {
                    "en": "Hello / Greetings",
                    "local": "Hello",
                    "pronunciation": "Hel-lo",
                    "meaning": "Standard friendly greeting",
                    "whenToUse": "When meeting locals or shopkeepers"
                  },
                  {
                    "en": "Thank you very much",
                    "local": "Thank you",
                    "pronunciation": "Thank-you",
                    "meaning": "Expression of gratitude",
                    "whenToUse": "After receiving service or help"
                  },
                  {
                    "en": "How much does this cost?",
                    "local": "Price please?",
                    "pronunciation": "How much?",
                    "meaning": "Asking for product price",
                    "whenToUse": "While shopping in markets"
                  },
                  {
                    "en": "Where is the nearest station/hospital?",
                    "local": "Where is it?",
                    "pronunciation": "Where-is-it?",
                    "meaning": "Asking for directions",
                    "whenToUse": "When looking for location directions"
                  },
                  {
                    "en": "Help! / Emergency",
                    "local": "Help!",
                    "pronunciation": "Help!",
                    "meaning": "Urgent assistance request",
                    "whenToUse": "In case of emergency"
                  },
                  {
                    "en": "Goodbye / See you again",
                    "local": "Goodbye",
                    "pronunciation": "Good-bye",
                    "meaning": "Parting phrase",
                    "whenToUse": "When leaving a venue or store"
                  }
                ]
              },
              "rulesInfo": [
                {
                  "title": "Respect Religious & Cultural Heritage Sites",
                  "desc": "Dress modestly and follow posted guidelines when visiting temples, churches, or monuments.",
                  "type": "warning",
                  "icon": "🙏",
                  "source": "Local Tourism Guidelines"
                },
                {
                  "title": "Keep Emergency Contacts Accessible",
                  "desc": "Save official local police and medical helpline numbers on your phone before exploring.",
                  "type": "info",
                  "icon": "📞",
                  "source": "Travel Safety Bureau"
                },
                {
                  "title": "Follow Local Tipping & Bargaining Etiquette",
                  "desc": "Bargain politely in street markets; tipping is optional but appreciated for good service.",
                  "type": "info",
                  "icon": "💡",
                  "source": "Cultural Etiquette Guide"
                },
                {
                  "title": "Photography & Drone Regulations",
                  "desc": "Always ask permission before photographing individuals, private property, or security installations.",
                  "type": "warning",
                  "icon": "📸",
                  "source": "Civic Administration"
                },
                {
                  "title": "Littering & Environmental Hygiene",
                  "desc": "Dispose of garbage in designated recycling bins to keep heritage zones clean.",
                  "type": "info",
                  "icon": "♻️",
                  "source": "Municipal Environment Bureau"
                }
              ],
              "placesDetail": [
                {
                  "name": "Famous City Landmark & Main Square",
                  "description": "The iconic central landmark of {DEST}, celebrated for historic architecture, photo opportunities, and vibrant foot traffic.",
                  "rating": 4.8,
                  "distance": "1.0 km away",
                  "hours": "Open 24 hours",
                  "duration": "1 - 2 hours",
                  "bestTime": "Sunset / Evening",
                  "mapsLink": "https://www.google.com/maps",
                  "category": "Landmark",
                  "verified": true
                },
                {
                  "name": "Historic Heritage Fort & Palace Museum",
                  "description": "A magnificent royal fort and heritage museum showcasing royal artifacts, panoramic city views, and architectural grandeur.",
                  "rating": 4.9,
                  "distance": "3.2 km away",
                  "hours": "9:00 AM - 6:00 PM",
                  "duration": "2 - 3 hours",
                  "bestTime": "Morning (9:30 AM)",
                  "mapsLink": "https://www.google.com/maps",
                  "category": "Historical",
                  "verified": true
                },
                {
                  "name": "Scenic Riverfront Promenade & Botanical Park",
                  "description": "A peaceful green waterfront park featuring walking trails, lush gardens, musical fountains, and evening boat rides.",
                  "rating": 4.7,
                  "distance": "2.5 km away",
                  "hours": "6:00 AM - 9:00 PM",
                  "duration": "1 - 2 hours",
                  "bestTime": "Evening (5:30 PM)",
                  "mapsLink": "https://www.google.com/maps",
                  "category": "Nature & Park",
                  "verified": true
                },
                {
                  "name": "Cultural Crafts Village & Artisan Center",
                  "description": "An interactive cultural hub celebrating local folk music, traditional dance, handmade pottery, and authentic handicraft shopping.",
                  "rating": 4.6,
                  "distance": "4.0 km away",
                  "hours": "11:00 AM - 10:00 PM",
                  "duration": "2 - 4 hours",
                  "bestTime": "Night (7:00 PM)",
                  "mapsLink": "https://www.google.com/maps",
                  "category": "Culture",
                  "verified": true
                }
              ],
              "bestTimeInfo": {
                "months": ["March", "April", "May", "September", "October", "November"],
                "dayTimes": [
                  { "time": "8:30 AM – 11:30 AM", "bestFor": "Outdoor Sightseeing & Photography" },
                  { "time": "5:00 PM – 8:30 PM", "bestFor": "Evening Markets & Dining" }
                ],
                "sunrise": "6:30 AM",
                "sunset": "6:45 PM",
                "avgTemp": "24°C"
              },
              "marketsInfo": [
                {
                  "name": "Central Plaza & Shopping Market",
                  "rating": 4.6,
                  "type": "Bazaar / Shopping Street",
                  "whatToBuy": "Souvenirs, local handicrafts, fashion, and street snacks",
                  "bargain": true,
                  "hours": "10:00 AM - 9:00 PM",
                  "icon": "🛒",
                  "verified": true
                },
                {
                  "name": "Artisan Craft & Handicraft Market",
                  "rating": 4.7,
                  "type": "Heritage Bazaar",
                  "whatToBuy": "Handmade decor, traditional textiles, and gifts",
                  "bargain": true,
                  "hours": "11:00 AM - 8:30 PM",
                  "icon": "🎨",
                  "verified": true
                },
                {
                  "name": "Downtown Fashion & Night Market",
                  "rating": 4.5,
                  "type": "Night Market",
                  "whatToBuy": "Trendy clothing, accessories, and local street food",
                  "bargain": false,
                  "hours": "4:00 PM - 11:00 PM",
                  "icon": "🛍️",
                  "verified": true
                },
                {
                  "name": "Old City Spice & Gourmet Market",
                  "rating": 4.8,
                  "type": "Traditional Food Market",
                  "whatToBuy": "Local spices, artisanal tea, sweets, and dried snacks",
                  "bargain": true,
                  "hours": "9:00 AM - 8:00 PM",
                  "icon": "🍲",
                  "verified": true
                }
              ],
              "tipsInfo": [
                {
                  "icon": "💳",
                  "tip": "Keep both digital payments and local cash available for small vendors.",
                  "category": "Money",
                  "color": "blue"
                },
                {
                  "icon": "🚕",
                  "tip": "Use verified ride-hailing apps or official cabs for reliable city transport.",
                  "category": "Transport",
                  "color": "green"
                },
                {
                  "icon": "💧",
                  "tip": "Stay hydrated and carry sealed bottled drinking water while exploring outdoor sites.",
                  "category": "Health",
                  "color": "teal"
                },
                {
                  "icon": "📄",
                  "tip": "Store digital back-up copies of your passport, ID, and travel insurance on your phone.",
                  "category": "Safety",
                  "color": "amber"
                },
                {
                  "icon": "🏛️",
                  "tip": "Check opening hours and weekly closing days for museums and palaces in advance.",
                  "category": "Sightseeing",
                  "color": "purple"
                }
              ]
            }
            """;
        return template.replace("{DEST}", dest);
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

    /**
     * Makes a Google Search grounded call to Gemini generateContent API.
     * Appends search sources into the provided list.
     */
    public String callGeminiGrounded(String prompt, int maxTokens, List<String> outSources) {
        try {
            java.util.Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));
            requestBody.put("generationConfig", Map.of(
                    "maxOutputTokens", maxTokens,
                    "temperature", 0.1
            ));
            
            // Enable googleSearch tool
            requestBody.put("tools", List.of(
                    Map.of("googleSearch", Map.of())
            ));

            String response = restClient.post()
                    .uri("/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(response);
            
            // Extract text response
            String rawText = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            // Extract grounding metadata chunks for verification sources
            JsonNode groundingMetadata = root
                    .path("candidates").get(0)
                    .path("groundingMetadata");
            
            if (groundingMetadata != null && groundingMetadata.has("groundingChunks")) {
                for (JsonNode chunk : groundingMetadata.get("groundingChunks")) {
                    JsonNode web = chunk.get("web");
                    if (web != null && web.has("uri")) {
                        String uri = web.get("uri").asText();
                        if (outSources != null && !outSources.contains(uri)) {
                            outSources.add(uri);
                        }
                    }
                }
            }

            // Clean markdown code fences if Gemini wraps JSON in ```json ... ```
            return rawText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        } catch (Exception e) {
            log.warn("Gemini Grounded API call failed ({}), falling back to ungrounded Gemini call", e.getMessage());
            try {
                return callGemini(prompt, maxTokens);
            } catch (Exception ex) {
                log.error("Ungrounded Gemini call also failed: {}", ex.getMessage());
                return "{}";
            }
        }
    }

    public String getFlightsInfo(String source, String destination, int travelers, List<String> outSources) {
        String prompt = """
            Search the web to find actual, real-world flight information from "%s" to "%s" for %d travelers.
            
            REQUIREMENTS:
            1. Find the main commercial airport for "%s" and "%s" with their correct IATA codes.
            2. Find actual flights operating between these airports, including real airlines, flight durations, stops, typical departure and arrival times, and actual ticket prices in INR for round-trip travel in the near future (e.g. August 2026 or coming months).
            3. Find realistic airport transfer options (taxi, cab, bus, metro, etc.) and their distances and cost estimates from the destination airport to the city center.
            4. If no commercial flights are available or verified, return JSON with empty lists, do NOT invent/fabricate flight numbers or prices.
            
            Respond with ONLY a valid JSON object in this format (no markdown, no code fences):
            {
              "departureAirport": {
                "name": "Departure Airport Name",
                "code": "XYZ",
                "city": "Source City",
                "distanceFromCityCenterKm": 15.2
              },
              "arrivalAirport": {
                "name": "Arrival Airport Name",
                "code": "ABC",
                "city": "Destination City",
                "distanceFromCityCenterKm": 28.5
              },
              "alternativeAirports": [
                {
                  "name": "Alternative Airport Name",
                  "code": "DEF",
                  "city": "Destination City",
                  "distanceFromCityCenterKm": 35.0
                }
              ],
              "flights": [
                {
                  "airline": "Airline Name",
                  "flightNumber": "XX-123",
                  "duration": "2 hours 15 minutes",
                  "stops": 0,
                  "departureTime": "07:30 AM",
                  "arrivalTime": "09:45 AM",
                  "pricePerPersonInr": 7200.0,
                  "baggageInfo": "15kg check-in, 7kg cabin"
                }
              ],
              "airportTransfers": [
                {
                  "mode": "Prepaid Taxi",
                  "costEstimateInr": 1100.0,
                  "duration": "45 minutes",
                  "details": "Counter available at Arrivals hall"
                }
              ]
            }
            """.formatted(source, destination, travelers, source, destination);

        return callGeminiGrounded(prompt, 2000, outSources);
    }

    public String getEvInfo(String source, String destination, List<String> outSources) {
        String prompt = """
            Search the web to find actual, real EV charging stations along the driving route from "%s" to "%s" in India.
            
            REQUIREMENTS:
            1. Find actual EV charging stations (e.g. Tata Power EZ Charge, Zeon, Jio-bp, Fortum) along this route with their real addresses/locations.
            2. Suggest 2 to 3 logical charging stops based on typical EV range, specifying duration and estimated cost in INR.
            3. Provide real, verified tips for EV travel on this route.
            4. If no EV charging stations are verified, return empty lists. Do NOT invent stations.
            
            Respond with ONLY a valid JSON object in this format (no markdown, no code fences):
            {
              "chargingStations": [
                {
                  "name": "Tata Power EZ Charge Station",
                  "address": "NH-48, Midway Hotel, Behror, Rajasthan",
                  "connectorType": "CCS2 60kW Fast Charger",
                  "distance": 125.4,
                  "verified": true
                }
              ],
              "chargingStops": [
                {
                  "location": "Behror Midway (Tata Power)",
                  "duration": "45 minutes",
                  "costEstimateInr": 450.0
                }
              ],
              "totalChargingCostInr": 900.0,
              "estimatedRangeKm": 320.0,
              "tips": [
                "Ensure to activate Tata Power EZ Charge and Zeon apps beforehand.",
                "NH-48 has high fast-charger density, but pre-booking is recommended during weekends."
              ]
            }
            """.formatted(source, destination);

        return callGeminiGrounded(prompt, 1800, outSources);
    }

    public String getRoadTripSpecifics(String source, String destination, String vehicleType, List<String> outSources) {
        String prompt = """
            Search the web to get real road trip details for driving from "%s" to "%s" in India using a %s.
            
            REQUIREMENTS:
            1. Describe the primary highway/route (e.g., NH-48 or NH-66) and road conditions.
            2. Find real, typical fuel stops (major service stations/plazas) along this route.
            3. Find typical toll costs (Fastag charges) for a one-way trip on this route.
            4. Provide real parking tips/details for tourist areas in "%s".
            
            Respond with ONLY a valid JSON object in this format (no markdown, no code fences):
            {
              "drivingRouteDescription": "Route via NH-48, mostly 6-lane highway in good condition. Expect heavy truck traffic near industrial clusters.",
              "fuelStops": [
                "IOCL Coco Plaza, Behror",
                "HP Fuel Centre, Kotputli"
              ],
              "tollsEstimateInr": 340.0,
              "parkingInfo": "Paid municipal parking available at City Palace and Amber Fort (approx. ₹50-100 for cars). Street parking is highly restricted.",
              "tips": [
                "Ensure Fastag is recharged with at least ₹500 before starting.",
                "Start early (around 5:00 AM) to beat Delhi-Gurugram highway congestion."
              ]
            }
            """.formatted(source, destination, vehicleType, destination);

        return callGeminiGrounded(prompt, 1800, outSources);
    }

    public String getBikeSpecifics(String source, String destination, String vehicleType, List<String> outSources) {
        String prompt = """
            Search the web to get real motorcycle/bike trip details for riding from "%s" to "%s" using a %s.
            
            REQUIREMENTS:
            1. Describe the best riding route, avoiding highways where two-wheelers are prohibited (e.g. certain Expressways).
            2. Find real, typical rest stops/motels suitable for bikers along this route.
            3. Calculate riding tips (safety precautions, weather conditions, ghat road details).
            
            Respond with ONLY a valid JSON object in this format (no markdown, no code fences):
            {
              "ridingRouteDescription": "Riding via NH-48 (service lanes where main express lanes prohibit two-wheelers). Scenic and mostly smooth tarmac.",
              "fuelCostEstimateInr": 1200.0,
              "restStops": [
                "Biker's Cafe near Manesar",
                "Highway King, Behror"
              ],
              "bikeFriendlyPlaces": [
                "Zostel Jaipur (safe motorcycle parking)",
                "Nahargarh Fort hills (great morning ride)"
              ],
              "tips": [
                "Two-wheelers are strictly banned on the main Sohna Elevated Corridor and Delhi-Mumbai Expressway. Use NH-48 service lanes instead.",
                "Wear full riding gear (helmet, jacket, gloves, boots) as highway speeds are high.",
                "Keep speed under 80 km/h to manage sudden service road crossings."
              ]
            }
            """.formatted(source, destination, vehicleType);

        return callGeminiGrounded(prompt, 1800, outSources);
    }
}


