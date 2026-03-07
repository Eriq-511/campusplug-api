package com.campusplug.api.users.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public class UpdateLocationRequest {

    @NotNull(message = "lat is required")
    @DecimalMin(value = "-90.0", message = "lat must be >= -90")
    @DecimalMax(value = "90.0", message = "lat must be <= 90")
    private Double lat;

    @NotNull(message = "lng is required")
    @DecimalMin(value = "-180.0", message = "lng must be >= -180")
    @DecimalMax(value = "180.0", message = "lng must be <= 180")
    private Double lng;

    /** Optional event type from Flutter geofence service */
    private String event; // ENTERED_CAMPUS | EXITED_CAMPUS | PERIODIC_UPDATE

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
}
