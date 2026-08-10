package com.tripplanner.controller;

import com.tripplanner.dto.request.TripRequest;
import com.tripplanner.dto.response.ApiResponse;
import com.tripplanner.dto.response.DashboardResponse;
import com.tripplanner.service.DashboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * DASHBOARD CONTROLLER
 *
 * Route: POST /api/v1/trip/dashboard
 *
 * DESIGN DECISIONS:
 *   - @Valid triggers DTO validation (fails fast before service is called)
 *   - No try/catch here — GlobalExceptionHandler handles all errors
 *   - Controller is "thin" — only routing + serialization, zero logic
 *
 * WHY NO @CrossOrigin HERE:
 *   CORS should be configured globally in a WebMvcConfigurer bean,
 *   not per-controller. Per-controller @CrossOrigin is fine for demos
 *   but in production you want centralized CORS config.
 *
 * CORS PRODUCTION CONFIG (add to a separate WebConfig.java):
 *   @Override
 *   public void addCorsMappings(CorsRegistry registry) {
 *       registry.addMapping("/api/**")
 *           .allowedOrigins("https://yourdomain.com")
 *           .allowedMethods("GET", "POST");
 *   }
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trip")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Main trip dashboard endpoint.
     *
     * Flow:
     *   1. @Valid validates TripRequest fields
     *   2. Check Redis cache (key = dashboard:{source}:{dest}:{days}:{group}:{vehicle})
     *   3. If MISS: fire 3 parallel CompletableFuture calls + fuel calculation
     *   4. Merge → cache → return aggregated DashboardResponse
     *
     * Expected latency:
     *   Cache HIT:  < 10ms
     *   Cache MISS: ~1.5-2.5s (parallel API calls)
     */
    @PostMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(
            @Valid @RequestBody TripRequest request) {

        log.info("Dashboard request: {} → {}, {} days, {} people, {}",
                request.getSource().getFormattedAddress(), request.getDestination().getFormattedAddress(),
                request.getDays(), request.getGroupSize(), request.getVehicleType());

        DashboardResponse response = dashboardService.getDashboard(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Simple health/ping endpoint (bypasses rate limiter via filter exclusion).
     * Useful for load balancer health checks and k8s readiness probes.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.ok("AI Trip Planner is running"));
    }
}
