package com.campusplug.api.users.dto;

public record UserProfileResponse(
        Long id,
        String fullName,
        String email,
        String registrationNumber,
        String phoneNumber,
        String campus,
        RegisteredLocation registeredLocation
) {

    public record RegisteredLocation(
            String label,
            Double lat,
            Double lng
    ) {
    }
}
