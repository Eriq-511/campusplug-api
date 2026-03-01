package com.campusplug.api.zones;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.users.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/location", "/location"})
public class LocationController {

    private final LocationCheckService locationCheckService;
    private final UserRepository userRepository;

    public LocationController(LocationCheckService locationCheckService,
                              UserRepository userRepository) {
        this.locationCheckService = locationCheckService;
        this.userRepository = userRepository;
    }

    /**
     * G8 — Flutter calls this every ~20m of movement.
     * Returns zone info + listing count for local notification.
     */
    @PostMapping("/check")
    public LocationCheckResponse checkZone(
            Authentication authentication,
            @Valid @RequestBody LocationCheckRequest request) {
        Long userId = userRepository.findByEmailIgnoreCase(authentication.getName())
                .map(u -> u.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
        return locationCheckService.checkZone(userId, request.getLat(), request.getLng());
    }
}
