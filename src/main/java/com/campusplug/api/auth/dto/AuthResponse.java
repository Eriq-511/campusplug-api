package com.campusplug.api.auth.dto;

public class AuthResponse {

    private String token;
    private UserSummary user;

    public AuthResponse(String token, UserSummary user) {
        this.token = token;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public UserSummary getUser() {
        return user;
    }

    public record UserSummary(
            Long id,
            String fullName,
            String email,
            String registrationNumber,
            String phoneNumber
    ) {
    }
}
