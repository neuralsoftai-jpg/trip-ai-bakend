package com.tripplanner.controller;

import com.tripplanner.dto.request.*;
import com.tripplanner.dto.response.*;
import com.tripplanner.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * FEATURES CONTROLLER
 *
 * All advanced features live under: POST /api/v1/trip/features/*
 *
 * WHY SEPARATE CONTROLLER (not in DashboardController):
 *   Single Responsibility Principle. Dashboard = core aggregation.
 *   Features = lazy-loaded sidebar features. Separate controllers
 *   make it easy to:
 *     - Apply different rate limits per feature group
 *     - Add authentication to premium features later
 *     - Scale feature services independently in microservices migration
 *
 * LAZY LOADING STRATEGY:
 *   The frontend calls /dashboard first (fast, cached).
 *   Feature endpoints are only called when the user clicks a sidebar item.
 *   This pattern is called "Lazy Loading" or "Progressive Enhancement".
 *   It reduces initial page load time and external API costs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trip/features")
@RequiredArgsConstructor
public class FeaturesController {

    private final PackingListService packingListService;
    private final RouteOptimizeService routeOptimizeService;
    private final CarbonFootprintService carbonFootprintService;
    private final BudgetService budgetService;
    private final EmergencyService emergencyService;
    private final PdfService pdfService;
    private final ChatService chatService;

    /**
     * POST /api/v1/trip/features/chat
     * AI Chat assistant for real-time travel recommendations
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            @Valid @RequestBody ChatRequest request) {

        log.info("Chat assistant request for: {}", request.getDestination());
        return ResponseEntity.ok(ApiResponse.ok(
                chatService.chat(request)));
    }


    /**
     * POST /api/v1/trip/features/packing-list
     * Sends weather + destination to Gemini → smart packing list
     */
    @PostMapping("/packing-list")
    public ResponseEntity<ApiResponse<PackingListResponse>> getPackingList(
            @Valid @RequestBody PackingRequest request) {

        log.info("Packing list request for: {}", request.getDestination());
        return ResponseEntity.ok(ApiResponse.ok(
                packingListService.getPackingList(request)));
    }

    /**
     * POST /api/v1/trip/features/route-optimize
     * TSP Nearest Neighbour on list of places → optimized visiting order
     */
    @PostMapping("/route-optimize")
    public ResponseEntity<ApiResponse<RouteResponse>> optimizeRoute(
            @Valid @RequestBody RouteRequest request) {

        log.info("Route optimize request: {} places in {}",
                request.getPlaces().size(), request.getDestination());
        return ResponseEntity.ok(ApiResponse.ok(
                routeOptimizeService.optimizeRoute(request)));
    }

    /**
     * POST /api/v1/trip/features/carbon-footprint
     * Pure calculation: distance × emission factor / group size
     */
    @PostMapping("/carbon-footprint")
    public ResponseEntity<ApiResponse<CarbonResponse>> getCarbonFootprint(
            @Valid @RequestBody CarbonRequest request) {

        log.info("Carbon request: {}km, {}", request.getDistanceKm(), request.getVehicleType());
        return ResponseEntity.ok(ApiResponse.ok(
                carbonFootprintService.calculate(request)));
    }

    /**
     * POST /api/v1/trip/features/budget
     * Gemini AI → tier-based budget estimate (Budget vs Luxury)
     */
    @PostMapping("/budget")
    public ResponseEntity<ApiResponse<BudgetResponse>> getBudget(
            @Valid @RequestBody BudgetRequest request) {

        log.info("Budget request: {} for {} days, {} people",
                request.getDestination(), request.getDays(), request.getGroupSize());
        return ResponseEntity.ok(ApiResponse.ok(
                budgetService.estimateBudget(request)));
    }

    /**
     * POST /api/v1/trip/features/emergency
     * Static DB → city-specific hospital, police, national helplines
     */
    @PostMapping("/emergency")
    public ResponseEntity<ApiResponse<EmergencyResponse>> getEmergencyContacts(
            @Valid @RequestBody EmergencyRequest request) {

        log.info("Emergency contacts request for: {}", request.getDestination());
        return ResponseEntity.ok(ApiResponse.ok(
                emergencyService.getEmergencyContacts(request)));
    }

    /**
     * POST /api/v1/trip/features/pdf
     * Generates and streams a downloadable PDF trip plan.
     *
     * WHY NOT ApiResponse<byte[]>:
     *   PDF is binary data, not JSON. Wrapping it in ApiResponse<byte[]>
     *   would require Base64 encoding which adds ~33% size overhead.
     *   Instead, we return ResponseEntity<byte[]> directly with proper
     *   Content-Type and Content-Disposition headers.
     *
     * Content-Disposition: attachment → browser triggers file download dialog.
     * Content-Disposition: inline    → browser tries to display it in-tab.
     */
    @PostMapping("/pdf")
    public ResponseEntity<byte[]> generatePdf(
            @Valid @RequestBody PdfRequest request) {

        log.info("PDF generation request: {} → {}", request.getSource(), request.getDestination());

        byte[] pdfBytes = pdfService.generateTripPdf(request);

        String filename = String.format("trip_%s_to_%s.pdf",
                request.getSource().toLowerCase().replace(" ", "_"),
                request.getDestination().toLowerCase().replace(" ", "_"));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(pdfBytes);
    }
}
