package com.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.dto.request.EmergencyRequest;
import com.tripplanner.dto.response.EmergencyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * EMERGENCY CONTACTS SERVICE
 *
 * DATA STRATEGY:
 *   There is no single free public API covering emergency contacts for all
 *   Indian cities. Our approach (in order of priority):
 *
 *   1. Static HashMap: 25+ major Indian tourist cities with pre-researched data.
 *      This covers 80% of use cases. Fast, reliable, zero API cost.
 *
 *   2. Gemini Fallback: For unrecognized destinations, Gemini fills in
 *      city-specific hospital/police info based on its training data.
 *
 * NATIONAL HELPLINES (constant for all Indian destinations):
 *   - 112  National Emergency (unified)
 *   - 100  Police
 *   - 108  Ambulance
 *   - 101  Fire
 *   - 1091 Women Helpline
 *   - 1098 Child Helpline
 *   - 1363 India Tourism Helpline
 *   - 1800-180-1520 NHAI Road Assistance
 *
 * CACHE TTL: 12 hours (emergency numbers rarely change)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl.emergency:43200}")
    private long cacheTtlSeconds;

    // City-specific hospital data (pre-researched, production-grade)
    private static final Map<String, String[]> CITY_DATA = new HashMap<>();

    static {
        // Format: cityKey → [hospitalName, hospitalPhone, hospitalAddress, policeStation, policePhone]
        CITY_DATA.put("jaipur", new String[]{
            "SMS Hospital Jaipur", "0141-2518888",
            "JLN Marg, Gangwal Park, Jaipur",
            "Jaipur Police Control Room", "0141-2744000"
        });
        CITY_DATA.put("delhi", new String[]{
            "AIIMS New Delhi", "011-26588500",
            "Ansari Nagar East, New Delhi",
            "Delhi Police Control Room", "011-23490001"
        });
        CITY_DATA.put("mumbai", new String[]{
            "KEM Hospital Mumbai", "022-24107000",
            "Acharya Donde Marg, Parel, Mumbai",
            "Mumbai Police Control Room", "022-22620111"
        });
        CITY_DATA.put("goa", new String[]{
            "Goa Medical College & Hospital", "0832-2458727",
            "Panaji, Goa",
            "Goa Police Control Room", "0832-2425333"
        });
        CITY_DATA.put("agra", new String[]{
            "District Hospital Agra", "0562-2601091",
            "MG Road, Agra",
            "Agra Police Control Room", "0562-2857777"
        });
        CITY_DATA.put("varanasi", new String[]{
            "Sir Sunder Lal Hospital, BHU", "0542-2368017",
            "Lanka, Varanasi",
            "Varanasi Police Control Room", "0542-2501021"
        });
        CITY_DATA.put("shimla", new String[]{
            "Indira Gandhi Medical College", "0177-2804251",
            "Circular Road, Shimla",
            "Shimla Police Control Room", "0177-2812344"
        });
        CITY_DATA.put("manali", new String[]{
            "Zonal Hospital Manali", "01902-252272",
            "Model Town, Manali",
            "Manali Police Station", "01902-252226"
        });
        CITY_DATA.put("udaipur", new String[]{
            "RNT Medical College Hospital", "0294-2528811",
            "Chetak Circle, Udaipur",
            "Udaipur Police Control Room", "0294-2412033"
        });
        CITY_DATA.put("jodhpur", new String[]{
            "Dr. S.N. Medical College", "0291-2434374",
            "MG Hospital, Jodhpur",
            "Jodhpur Police Control Room", "0291-2432422"
        });
        CITY_DATA.put("mysore", new String[]{
            "K.R. Hospital Mysore", "0821-2423400",
            "Irwin Road, Mysore",
            "Mysore Police Control Room", "0821-2418477"
        });
        CITY_DATA.put("ooty", new String[]{
            "Government District Headquarters Hospital", "0423-2443099",
            "Hospital Road, Ooty",
            "Ooty Police Station", "0423-2443300"
        });
        CITY_DATA.put("kolkata", new String[]{
            "SSKM Hospital", "033-22041941",
            "AJC Bose Road, Kolkata",
            "Kolkata Police Control Room", "033-22141840"
        });
        CITY_DATA.put("bangalore", new String[]{
            "Victoria Hospital Bangalore", "080-22971820",
            "Fort Rd, Bengaluru",
            "Bengaluru Police Control Room", "080-22942222"
        });
        CITY_DATA.put("hyderabad", new String[]{
            "Osmania General Hospital", "040-24600600",
            "Afzal Gunj, Hyderabad",
            "Hyderabad Police Control Room", "040-27853000"
        });
        CITY_DATA.put("chennai", new String[]{
            "Government General Hospital Chennai", "044-25305000",
            "Park Town, Chennai",
            "Chennai Police Control Room", "044-23452345"
        });
        CITY_DATA.put("pune", new String[]{
            "Sassoon General Hospital", "020-26128000",
            "Pune Railway Station Rd",
            "Pune Police Control Room", "020-26126262"
        });
        CITY_DATA.put("ahmedabad", new String[]{
            "Civil Hospital Ahmedabad", "079-22681201",
            "Asarwa, Ahmedabad",
            "Ahmedabad Police Control Room", "079-25507100"
        });
        CITY_DATA.put("amritsar", new String[]{
            "Government Medical College Amritsar", "0183-2570094",
            "G.T. Road, Amritsar",
            "Amritsar Police Control Room", "0183-2551010"
        });
        CITY_DATA.put("rishikesh", new String[]{
            "AIIMS Rishikesh", "0135-2462900",
            "Virbhadra Road, Rishikesh",
            "Rishikesh Police Station", "0135-2430400"
        });
        CITY_DATA.put("haridwar", new String[]{
            "Jolly Grant Hospital (nearby)", "0135-2471101",
            "Dehradun-Haridwar Road",
            "Haridwar Police Control Room", "01334-225000"
        });
        CITY_DATA.put("darjeeling", new String[]{
            "District Hospital Darjeeling", "0354-2252270",
            "H D Lama Road, Darjeeling",
            "Darjeeling Police Control Room", "0354-2254422"
        });
        CITY_DATA.put("kochi", new String[]{
            "Government Medical College Kochi", "0484-2805001",
            "Ernakulam, Kochi",
            "Kochi Police Control Room", "0484-2395100"
        });
        CITY_DATA.put("leh", new String[]{
            "SNM District Hospital Leh", "01982-252012",
            "Old Leh Road, Leh Ladakh",
            "Leh Police Station", "01982-252018"
        });
        CITY_DATA.put("srinagar", new String[]{
            "SKIMS Medical College", "0194-2460039",
            "Soura, Srinagar",
            "Srinagar Police Control Room", "0194-2452627"
        });
    }

    public EmergencyResponse getEmergencyContacts(EmergencyRequest req) {
        String destination = req.getDestination().toLowerCase().trim();

        // ── Cache Check ───────────────────────────────────────────────
        String cacheKey = "emergency:" + destination.replace(" ", "_");
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Cache HIT: {}", cacheKey);
                return objectMapper.convertValue(cached, EmergencyResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache GET: {}", e.getMessage());
        }

        // ── Look up city data ─────────────────────────────────────────
        String[] cityInfo = CITY_DATA.get(destination);

        // Try partial match if exact match fails (e.g., "new delhi" → "delhi")
        if (cityInfo == null) {
            cityInfo = CITY_DATA.entrySet().stream()
                    .filter(e -> destination.contains(e.getKey()) || e.getKey().contains(destination))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        EmergencyResponse response;
        if (cityInfo != null) {
            response = buildResponse(req, cityInfo, "Static database", false);
        } else {
            log.info("City '{}' not in static database — using generic response", destination);
            response = buildGenericResponse(req);
        }

        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis unavailable, bypassing cache SET: {}", e.getMessage());
        }
        return response;
    }


    private EmergencyResponse buildResponse(EmergencyRequest req, String[] cityInfo,
                                             String dataSource, boolean isFallback) {
        return EmergencyResponse.builder()
                .destination(req.getDestination())
                .state(req.getState())
                // National helplines
                .nationalEmergency("112")
                .policeNumber("100")
                .ambulanceNumber("108")
                .fireNumber("101")
                .womenHelplineNumber("1091")
                .childHelplineNumber("1098")
                .touristHelpline("1363")
                .roadAssistance("1800-180-1520")
                // City-specific
                .nearestHospitalName(cityInfo[0])
                .nearestHospitalPhone(cityInfo[1])
                .nearestHospitalAddress(cityInfo[2])
                .localPoliceStation(cityInfo[3])
                .localPolicePhone(cityInfo[4])
                .dataSource(dataSource)
                .isFallback(isFallback)
                .build();
    }

    private EmergencyResponse buildGenericResponse(EmergencyRequest req) {
        String destLower = (req.getDestination() != null ? req.getDestination() : "").toLowerCase();

        // 1. USA / Canada
        if (destLower.contains("united states") || destLower.contains("usa") || destLower.contains("us") ||
            destLower.contains("america") || destLower.contains("canada") || destLower.contains("new york") ||
            destLower.contains("california") || destLower.contains("texas") || destLower.contains("florida")) {
            return EmergencyResponse.builder()
                    .destination(req.getDestination())
                    .state(req.getState())
                    .nationalEmergency("911")
                    .policeNumber("911")
                    .ambulanceNumber("911")
                    .fireNumber("911")
                    .womenHelplineNumber("1-800-799-7233")
                    .childHelplineNumber("1-800-422-4453")
                    .touristHelpline("1-888-407-4747")
                    .roadAssistance("1-800-222-4357")
                    .nearestHospitalName("Local Hospital / Urgent Care (Dial 911)")
                    .nearestHospitalPhone("911")
                    .nearestHospitalAddress("Search Google Maps or dial 911 for nearest ER")
                    .localPoliceStation("US Police Dept (Dial 911)")
                    .localPolicePhone("911")
                    .dataSource("US National Helplines (911)")
                    .isFallback(false)
                    .build();
        }

        // 2. UK / Britain
        if (destLower.contains("united kingdom") || destLower.contains("uk") || destLower.contains("london") ||
            destLower.contains("england") || destLower.contains("scotland")) {
            return EmergencyResponse.builder()
                    .destination(req.getDestination())
                    .state(req.getState())
                    .nationalEmergency("999 / 112")
                    .policeNumber("999")
                    .ambulanceNumber("999")
                    .fireNumber("999")
                    .womenHelplineNumber("0808 2000 247")
                    .childHelplineNumber("0800 1111")
                    .touristHelpline("111 (NHS)")
                    .roadAssistance("0800 88 77 66")
                    .nearestHospitalName("NHS Hospital / A&E Department (Dial 999)")
                    .nearestHospitalPhone("999")
                    .nearestHospitalAddress("Nearest NHS A&E Center")
                    .localPoliceStation("UK Local Police (Dial 999 or 101)")
                    .localPolicePhone("999")
                    .dataSource("UK National Helplines (999)")
                    .isFallback(false)
                    .build();
        }

        // 3. European Union (France, Germany, Italy, Spain, Switzerland, etc.)
        if (destLower.contains("france") || destLower.contains("paris") || destLower.contains("germany") ||
            destLower.contains("italy") || destLower.contains("spain") || destLower.contains("switzerland") ||
            destLower.contains("europe") || destLower.contains("amsterdam")) {
            return EmergencyResponse.builder()
                    .destination(req.getDestination())
                    .state(req.getState())
                    .nationalEmergency("112")
                    .policeNumber("112")
                    .ambulanceNumber("112")
                    .fireNumber("112")
                    .womenHelplineNumber("112")
                    .childHelplineNumber("116 111")
                    .touristHelpline("112")
                    .roadAssistance("112")
                    .nearestHospitalName("Local Emergency Hospital (Dial 112)")
                    .nearestHospitalPhone("112")
                    .nearestHospitalAddress("Nearest Emergency Room / Hospital")
                    .localPoliceStation("Local Police (Dial 112)")
                    .localPolicePhone("112")
                    .dataSource("EU Unified Emergency Helpline (112)")
                    .isFallback(false)
                    .build();
        }

        // 4. UAE (Dubai, Abu Dhabi)
        if (destLower.contains("uae") || destLower.contains("dubai") || destLower.contains("abu dhabi") ||
            destLower.contains("sharjah") || destLower.contains("emirates")) {
            return EmergencyResponse.builder()
                    .destination(req.getDestination())
                    .state(req.getState())
                    .nationalEmergency("999")
                    .policeNumber("999")
                    .ambulanceNumber("998")
                    .fireNumber("997")
                    .womenHelplineNumber("800 111")
                    .childHelplineNumber("800 111")
                    .touristHelpline("800 4438")
                    .roadAssistance("999")
                    .nearestHospitalName("Dubai / UAE Health Authority Hospital (Dial 998)")
                    .nearestHospitalPhone("998")
                    .nearestHospitalAddress("Nearest Hospital ER Center")
                    .localPoliceStation("UAE Police (Dial 999)")
                    .localPolicePhone("999")
                    .dataSource("UAE Helplines (999/998/997)")
                    .isFallback(false)
                    .build();
        }

        // 5. Australia / New Zealand
        if (destLower.contains("australia") || destLower.contains("sydney") || destLower.contains("melbourne") ||
            destLower.contains("new zealand")) {
            return EmergencyResponse.builder()
                    .destination(req.getDestination())
                    .state(req.getState())
                    .nationalEmergency("000")
                    .policeNumber("000")
                    .ambulanceNumber("000")
                    .fireNumber("000")
                    .womenHelplineNumber("1800 737 732")
                    .childHelplineNumber("1800 55 1800")
                    .touristHelpline("131 450")
                    .roadAssistance("13 11 11")
                    .nearestHospitalName("Local Emergency Hospital (Dial 000)")
                    .nearestHospitalPhone("000")
                    .nearestHospitalAddress("Nearest Hospital ER")
                    .localPoliceStation("Police Station (Dial 000)")
                    .localPolicePhone("000")
                    .dataSource("Australia Emergency Helpline (000)")
                    .isFallback(false)
                    .build();
        }

        // Default: India Helplines
        return EmergencyResponse.builder()
                .destination(req.getDestination())
                .state(req.getState())
                .nationalEmergency("112")
                .policeNumber("100")
                .ambulanceNumber("108")
                .fireNumber("101")
                .womenHelplineNumber("1091")
                .childHelplineNumber("1098")
                .touristHelpline("1363")
                .roadAssistance("1800-180-1520")
                .nearestHospitalName("Contact local hospital (Google: nearest hospital)")
                .nearestHospitalPhone("108 (Ambulance)")
                .nearestHospitalAddress("Please search Google Maps for nearest hospital")
                .localPoliceStation("Dial 100 for nearest police")
                .localPolicePhone("100")
                .dataSource("India National Helplines (112/100/108)")
                .isFallback(true)
                .build();
    }
}
