package com.campusplug.api.auth.dto;

import com.campusplug.api.users.dto.RegisteredLocationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @NotBlank
    private String fullName;

    @NotBlank
    private String registrationNumber;

    @NotBlank
    @Email
    private String email;

    private String phoneNumber;

    @Size(max = 50)
    private String campus;

    @Valid
    private RegisteredLocationDto registeredLocation;

    @Valid
    private RegisteredLocationDto alternateLocation;

    @NotBlank
    @Size(min = 8, max = 72)
    private String password;

    @NotBlank
    @Size(min = 8, max = 72)
    private String confirmPassword;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
