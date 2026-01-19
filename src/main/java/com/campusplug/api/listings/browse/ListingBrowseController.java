package com.campusplug.api.listings.browse;

import com.campusplug.api.listings.browse.dto.ListingPageResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/listings", "/listings"})
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
}
