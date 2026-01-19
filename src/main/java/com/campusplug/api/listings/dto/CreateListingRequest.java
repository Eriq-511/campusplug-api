package com.campusplug.api.listings.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateListingRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotNull
    @JsonAlias("price")
    private Long priceUgx;

    @NotBlank
    @Size(max = 50)
    private String categoryCode;

    @Size(max = 500)
    private String description;

    private boolean useRegisteredLocation;

    @Size(max = 200)
    private String locationText;

    private Double lat;

    private Double lng;

    @Size(max = 50)
    private String campus;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getPriceUgx() {
        return priceUgx;
    }

    public void setPriceUgx(Long priceUgx) {
        this.priceUgx = priceUgx;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isUseRegisteredLocation() {
        return useRegisteredLocation;
    }

    public void setUseRegisteredLocation(boolean useRegisteredLocation) {
        this.useRegisteredLocation = useRegisteredLocation;
    }

    public String getLocationText() {
        return locationText;
    }

    public void setLocationText(String locationText) {
        this.locationText = locationText;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public String getCampus() {
        return campus;
    }

    public void setCampus(String campus) {
        this.campus = campus;
    }
}
