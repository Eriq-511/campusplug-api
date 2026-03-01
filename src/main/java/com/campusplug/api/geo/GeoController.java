package com.campusplug.api.geo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/geo", "/geo"})
public class GeoController {

    private final GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    /** G2 — Forward geocode: ?address=MUST+Mbarara */
    @GetMapping("/geocode")
    public GeoResponse geocode(@RequestParam @NotBlank String address) {
        return geoService.geocode(address);
    }

    /** G2 — Reverse geocode: ?lat=-0.6089&lng=30.6570 */
    @GetMapping("/reverse")
    public GeoResponse reverse(
            @RequestParam @NotNull Double lat,
            @RequestParam @NotNull Double lng) {
        return geoService.reverseGeocode(lat, lng);
    }
}
