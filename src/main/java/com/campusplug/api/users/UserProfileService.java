package com.campusplug.api.users;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.users.dto.RegisteredLocationDto;
import com.campusplug.api.users.dto.UpdateUserProfileRequest;
import com.campusplug.api.users.dto.UserProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class UserProfileService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
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

        if (req.getRegisteredLocation() != null) {
            applyRegisteredLocation(user, req.getRegisteredLocation());
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

    private static UserProfileResponse toResponse(UserRepository.UserProfileProjection p) {
        UserProfileResponse.RegisteredLocation location = null;
        if (p.getRegisteredLat() != null && p.getRegisteredLng() != null) {
            location = new UserProfileResponse.RegisteredLocation(
                    p.getRegisteredLocationText(),
                    p.getRegisteredLat(),
                    p.getRegisteredLng()
            );
        }

        return new UserProfileResponse(
                p.getId(),
                p.getFullName(),
                p.getEmail(),
                p.getRegistrationNumber(),
                p.getPhoneNumber(),
                p.getCampus(),
                location
        );
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
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
