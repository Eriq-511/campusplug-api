package com.campusplug.api.geo;

import com.campusplug.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Service
public class GeoService {

    private static final String GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json";

    @Value("${app.google.maps-api-key:}")
    private String mapsApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Forward geocode: address string → lat/lng
     * GET /api/v1/geo/geocode?address=MUST+Mbarara
     */
    public GeoResponse geocode(String address) {
        if (mapsApiKey == null || mapsApiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEO_DISABLED",
                    "Google Maps API key not configured");
        }

        String url = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                .queryParam("address", address)
                .queryParam("key", mapsApiKey)
                .toUriString();

        Map<?, ?> response = restTemplate.getForObject(url, Map.class);
        return parseGeocodeResponse(response, address);
    }

    /**
     * Reverse geocode: lat/lng → address string
     * GET /api/v1/geo/reverse?lat=-0.6089&lng=30.6570
     */
    public GeoResponse reverseGeocode(double lat, double lng) {
        if (mapsApiKey == null || mapsApiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEO_DISABLED",
                    "Google Maps API key not configured");
        }

        String latlng = lat + "," + lng;
        String url = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                .queryParam("latlng", latlng)
                .queryParam("key", mapsApiKey)
                .toUriString();

        Map<?, ?> response = restTemplate.getForObject(url, Map.class);
        return parseReverseResponse(response, lat, lng);
    }

    private GeoResponse parseGeocodeResponse(Map<?, ?> body, String query) {
        String status = body == null ? null : (String) body.get("status");
        if (!"OK".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GEO_NOT_FOUND",
                    "Geocoding failed for: " + query + " (status=" + status + ")");
        }

        List<?> results = (List<?>) body.get("results");
        if (results == null || results.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GEO_NOT_FOUND", "No results for: " + query);
        }

        Map<?, ?> first = (Map<?, ?>) results.get(0);
        String formattedAddress = (String) first.get("formatted_address");
        Map<?, ?> geometry = (Map<?, ?>) first.get("geometry");
        Map<?, ?> location = (Map<?, ?>) geometry.get("location");

        double lat = ((Number) location.get("lat")).doubleValue();
        double lng = ((Number) location.get("lng")).doubleValue();
        return new GeoResponse(lat, lng, formattedAddress);
    }

    private GeoResponse parseReverseResponse(Map<?, ?> body, double lat, double lng) {
        String status = body == null ? null : (String) body.get("status");
        if (!"OK".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GEO_NOT_FOUND",
                    "Reverse geocoding failed (status=" + status + ")");
        }

        List<?> results = (List<?>) body.get("results");
        String address = (results != null && !results.isEmpty())
                ? (String) ((Map<?, ?>) results.get(0)).get("formatted_address")
                : "Unknown location";

        return new GeoResponse(lat, lng, address);
    }
}
