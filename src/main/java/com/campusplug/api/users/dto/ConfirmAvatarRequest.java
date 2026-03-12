package com.campusplug.api.users.dto;

import jakarta.validation.constraints.NotBlank;

public class ConfirmAvatarRequest {

    @NotBlank
    private String avatarUrl;

    @NotBlank
    private String avatarPublicId;

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getAvatarPublicId() {
        return avatarPublicId;
    }

    public void setAvatarPublicId(String avatarPublicId) {
        this.avatarPublicId = avatarPublicId;
    }
}
