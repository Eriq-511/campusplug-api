package com.campusplug.api.listings.browse;

import com.campusplug.api.listings.browse.dto.ListingPageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/listings", "/api/listings", "/listings"})
public class ListingBrowseController {

    private final ListingBrowseService browseService;

    public ListingBrowseController(ListingBrowseService browseService) {
        this.browseService = browseService;
    }

    @GetMapping("/search")
    public ListingPageResponse search(
            Authentication ignored,
            @RequestParam("query") String query,
            @RequestParam(name = "categoryCode", required = false) String categoryCode,
            @RequestParam(name = "campus", required = false) String campus,
            @RequestParam(name = "minPriceUgx", required = false) Long minPriceUgx,
            @RequestParam(name = "maxPriceUgx", required = false) Long maxPriceUgx,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return browseService.search(query, categoryCode, campus, minPriceUgx, maxPriceUgx, page, size);
    }

    @GetMapping("/nearby")
    public ListingPageResponse nearby(
            Authentication ignored,
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam("radiusKm") double radiusKm,
            @RequestParam(name = "categoryCode", required = false) String categoryCode,
            @RequestParam(name = "campus", required = false) String campus,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return browseService.nearby(lat, lng, radiusKm, categoryCode, campus, page, size);
    }

    /** G9 — listings filtered by campus zone, sorted by proximity */
    @GetMapping("/zone/{tag}")
    public ListingPageResponse byZone(
            Authentication ignored,
            @PathVariable("tag") String tag,
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return browseService.byZone(tag, lat, lng, page, size);
    }

    /** G9 — count active listings in a zone (for notification text) */
    @GetMapping("/zone/{tag}/count")
    public ResponseEntity<Map<String, Object>> zoneCount(
            Authentication ignored,
            @PathVariable("tag") String tag) {
        long count = browseService.countByZone(tag);
        return ResponseEntity.ok(Map.of("zoneTag", tag, "count", count));
    }

    /** G9 — global feed sorted by distance */
    @GetMapping("/feed")
    public ListingPageResponse feed(
            Authentication ignored,
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return browseService.allByDistance(lat, lng, page, size);
    }

    /** Get single listing detail by ID (for product card click) */
    @GetMapping("/{id}")
    public com.campusplug.api.listings.dto.ListingResponse getById(
            Authentication ignored,
            @PathVariable("id") Long id) {
        return browseService.getById(id);
    }
}
