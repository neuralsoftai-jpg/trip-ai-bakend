package com.tripplanner.controller;

import com.tripplanner.dto.ResolvedLocation;
import com.tripplanner.dto.response.ApiResponse;
import com.tripplanner.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/trip/location")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ResolvedLocation>>> searchLocations(
            @RequestParam("query") String query) {
        
        log.info("REST API Autocomplete search: '{}'", query);
        List<ResolvedLocation> results = locationService.searchLocations(query);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }
}
