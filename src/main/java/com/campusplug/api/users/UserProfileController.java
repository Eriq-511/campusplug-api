package com.campusplug.api.users;

import com.campusplug.api.users.dto.UpdateUserProfileRequest;
import com.campusplug.api.users.dto.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/users", "/users"})
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/profile")
    public UserProfileResponse getProfile(Authentication authentication) {
        return userProfileService.getProfile(authentication.getName());
    }

    @PutMapping("/profile")
    public UserProfileResponse updateProfile(Authentication authentication, @Valid @RequestBody UpdateUserProfileRequest request) {
        return userProfileService.updateProfile(authentication.getName(), request);
    }
}
