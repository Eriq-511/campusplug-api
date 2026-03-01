package com.campusplug.api.users;

import com.campusplug.api.users.dto.UpdateUserProfileRequest;
import com.campusplug.api.users.dto.UserProfileResponse;
import com.campusplug.api.users.dto.UpdateLocationRequest;
import com.campusplug.api.users.dto.FcmTokenRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    /** G3 — Flutter geofence_service / workmanager calls this on enter/exit/periodic */
    @PutMapping("/location")
    public ResponseEntity<Map<String, String>> updateLocation(
            Authentication authentication,
            @Valid @RequestBody UpdateLocationRequest request) {
        userProfileService.updateLastLocation(authentication.getName(), request);
        return ResponseEntity.ok(Map.of("message", "Location updated"));
    }

    /** G4 — Flutter registers FCM device token on app startup */
    @PutMapping("/fcm-token")
    public ResponseEntity<Map<String, String>> updateFcmToken(
            Authentication authentication,
            @Valid @RequestBody FcmTokenRequest request) {
        userProfileService.updateFcmToken(authentication.getName(), request.getToken());
        return ResponseEntity.ok(Map.of("message", "FCM token registered"));
    }
}
