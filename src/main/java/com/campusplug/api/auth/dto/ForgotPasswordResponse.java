package com.campusplug.api.auth.dto;

public class ForgotPasswordResponse {

    private String message;
    private String resetToken;

    public ForgotPasswordResponse(String message, String resetToken) {
        this.message = message;
        this.resetToken = resetToken;
    }

    public String getMessage() {
        return message;
    }

    public String getResetToken() {
        return resetToken;
    }
}
