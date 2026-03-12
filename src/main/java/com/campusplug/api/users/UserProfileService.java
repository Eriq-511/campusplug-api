package com.campusplug.api.users;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.listings.ListingRepository;
import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.uploads.cloudinary.CloudinaryProperties;
import com.campusplug.api.users.dto.ConfirmAvatarRequest;
import com.campusplug.api.users.dto.PublicUserProfileResponse;
import com.campusplug.api.users.dto.RegisteredLocationDto;
import com.campusplug.api.users.dto.UpdateLocationRequest;
import com.campusplug.api.users.dto.UpdateUserProfileRequest;
import com.campusplug.api.users.dto.UserProfileResponse;
import com.campusplug.api.zones.LocationCheckService;

@Service
public class UserProfileService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final LocationCheckService locationCheckService;
    private final CloudinaryProperties cloudinaryProperties;

    public UserProfileService(UserRepository userRepository,
                              ListingRepository listingRepository,
                              LocationCheckService locationCheckService,
                              CloudinaryProperties cloudinaryProperties) {
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.locationCheckService = locationCheckService;
        this.cloudinaryProperties = cloudinaryProperties;
    }

    public UserProfileResponse getProfile(String email) {
        UserRepository.UserProfileProjection p = userRepository.findProfileByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
        return toResponse(p);
    }

    @Transactional
    public UserProfileResponse updateProfile(String email, UpdateUserProfileRequest req) {
        if (req.getEmail() != null || req.getRegistrationNumber() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FORBIDDEN_FIELD_UPDATE", "email and registrationNumber are immutable");
        }

        UserEntity user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));

        if (req.getFullName() != null) {
            String name = req.getFullName().trim();
            if (name.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "fullName must not be blank");
            }
            user.setFullName(name);
        }

        if (req.getPhoneNumber() != null) {
            String phone = normalizePhone(req.getPhoneNumber());
            if (phone != null && !E164.matcher(phone).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER", "phoneNumber must be E.164 (e.g. +256700000000)");
            }
            user.setPhoneNumber(phone);
        }

        if (req.getCampus() != null) {
            String campus = req.getCampus().trim();
            user.setCampus(campus.isBlank() ? null : campus.toLowerCase(Locale.ROOT));
        }

        if (req.getRegisteredLocation() != null && req.getAlternateLocation() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOCATION_CONFLICT", "Provide only one of registeredLocation or alternateLocation");
        }

        if (req.getRegisteredLocation() != null) {
            applyRegisteredLocation(user, req.getRegisteredLocation());
        }

        if (req.getAlternateLocation() != null) {
            applyAlternateLocation(user, req.getAlternateLocation());
        }

        userRepository.save(user);
        return getProfile(user.getEmail());
    }

    private void applyRegisteredLocation(UserEntity user, RegisteredLocationDto loc) {
        String label = loc.getLabel() == null ? null : loc.getLabel().trim();
        Double lat = loc.getLat();
        Double lng = loc.getLng();

        boolean allEmpty = (label == null || label.isBlank()) && lat == null && lng == null;
        if (allEmpty) {
            user.setRegisteredLocationText(null);
            userRepository.clearRegisteredGeo(user.getId());
            return;
        }

        if (label == null || label.isBlank() || lat == null || lng == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "registeredLocation requires label, lat and lng");
        }

        user.setRegisteredLocationText(label);
        userRepository.updateRegisteredGeo(user.getId(), lat, lng);
    }

    private void applyAlternateLocation(UserEntity user, RegisteredLocationDto loc) {
        String label = loc.getLabel() == null ? null : loc.getLabel().trim();
        Double lat = loc.getLat();
        Double lng = loc.getLng();

        boolean allEmpty = (label == null || label.isBlank()) && lat == null && lng == null;
        if (allEmpty) {
            user.setAlternateLocationText(null);
            userRepository.clearAlternateGeo(user.getId());
            return;
        }

        if (label == null || label.isBlank() || lat == null || lng == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "alternateLocation requires label, lat and lng");
        }

        user.setAlternateLocationText(label);
        userRepository.updateAlternateGeo(user.getId(), lat, lng);
    }

    private static UserProfileResponse toResponse(UserRepository.UserProfileProjection p) {
        UserProfileResponse.RegisteredLocation location = null;
        if (p.getRegisteredLat() != null && p.getRegisteredLng() != null) {
            location = new UserProfileResponse.RegisteredLocation(
                    p.getRegisteredLocationText(),
                    p.getRegisteredLat(),
                    p.getRegisteredLng()
            );
        }

        UserProfileResponse.RegisteredLocation alternate = null;
        if (p.getAlternateLat() != null && p.getAlternateLng() != null) {
            alternate = new UserProfileResponse.RegisteredLocation(
                p.getAlternateLocationText(),
                p.getAlternateLat(),
                p.getAlternateLng()
            );
        }

        return new UserProfileResponse(
                p.getId(),
                p.getFullName(),
                p.getEmail(),
                p.getRegistrationNumber(),
                p.getPhoneNumber(),
                p.getCampus(),
                p.getAvatarUrl(),
            location,
            alternate
        );
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the publicly visible profile for any user by ID.
     * Safe to expose to any authenticated student — no credentials or device tokens.
     */
    public PublicUserProfileResponse getPublicProfile(Long userId) {
        UserRepository.PublicUserProjection p = userRepository.findPublicById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));
        long activeListings = listingRepository.countByOwnerUserIdAndStatus(userId, ListingStatus.ACTIVE);
        return new PublicUserProfileResponse(
                p.getId(),
                p.getFullName(),
                p.getCampus(),
                p.getAvatarUrl(),
                activeListings,
                p.getCreatedAt()
        );
    }

    // G3 — update live device location
    @Transactional
    public void updateLastLocation(String email, UpdateLocationRequest req) {
        UserEntity user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
        userRepository.updateLastLocation(user.getId(), req.getLat(), req.getLng());

        // Keep geofencing state in sync with the latest device location.
        // This updates user_zones (and implicitly drives zone-based UX in the app).
        locationCheckService.checkZone(user.getId(), req.getLat(), req.getLng());
    }

    // G4 — store FCM push token
    @Transactional
    public void updateFcmToken(String email, String token) {
        UserEntity user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
        userRepository.updateFcmToken(user.getId(), token);
    }

    // Avatar — persist the Cloudinary URL after client-side upload
    @Transactional
    public UserProfileResponse updateAvatar(String email, ConfirmAvatarRequest req) {
        UserEntity user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));

        String avatarUrl = req.getAvatarUrl().trim();
        String avatarPublicId = req.getAvatarPublicId().trim();
        validateAvatarUpload(user.getId(), avatarUrl, avatarPublicId);

        userRepository.updateAvatar(user.getId(), avatarUrl, avatarPublicId);
        return getProfile(email);
    }

    private void validateAvatarUpload(Long userId, String avatarUrl, String avatarPublicId) {
        String expectedPublicId = "campusplug/avatars/" + userId + "/profile";
        if (!expectedPublicId.equals(avatarPublicId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR_PUBLIC_ID", "avatarPublicId does not match the signed avatar upload target");
        }

        String cloudName = cloudinaryProperties.getCloudName();
        if (cloudName == null || cloudName.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CLOUDINARY_NOT_CONFIGURED", "Cloudinary is not configured");
        }

        String expectedUrlPrefix = "https://res.cloudinary.com/" + cloudName.trim() + "/image/upload/";
        if (!avatarUrl.startsWith(expectedUrlPrefix) || !avatarUrl.contains("/" + expectedPublicId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR_URL", "avatarUrl does not match the signed Cloudinary upload target");
        }
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.replace(" ", "");
    }
}
