package com.campusplug.api.users.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public class UpdateUserProfileRequest {

    @Size(min = 1, max = 200)
    private String fullName;

    private String phoneNumber;

    @Size(min = 1, max = 50)
    private String campus;

    @Valid
    private RegisteredLocationDto registeredLocation;

    @Valid
    private RegisteredLocationDto alternateLocation;

    // Immutable fields: if provided by client, reject.
    private String email;

    private String registrationNumber;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getCampus() {
        return campus;
    }

    public void setCampus(String campus) {
        this.campus = campus;
    }

    public RegisteredLocationDto getRegisteredLocation() {
        return registeredLocation;
    }

    public void setRegisteredLocation(RegisteredLocationDto registeredLocation) {
        this.registeredLocation = registeredLocation;
    }

    public RegisteredLocationDto getAlternateLocation() {
        return alternateLocation;
    }

    public void setAlternateLocation(RegisteredLocationDto alternateLocation) {
        this.alternateLocation = alternateLocation;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }
}
