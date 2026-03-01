package com.campusplug.api.zones;

import jakarta.validation.constraints.NotNull;

public class LocationCheckRequest {

    @NotNull(message = "lat is required")
    private Double lat;

    @NotNull(message = "lng is required")
    private Double lng;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
}
