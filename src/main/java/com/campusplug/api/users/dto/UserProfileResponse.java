package com.campusplug.api.users.dto;

public record UserProfileResponse(
        Long id,
        String fullName,
        String email,
        String registrationNumber,
        String phoneNumber,
        String campus,
        String avatarUrl,
        RegisteredLocation registeredLocation,
        RegisteredLocation alternateLocation
) {

    public record RegisteredLocation(
            String label,
            Double lat,
            Double lng
    ) {
    }
}
