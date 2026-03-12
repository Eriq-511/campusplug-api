package com.campusplug.api.uploads.cloudinary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.listings.ListingEntity;
import com.campusplug.api.listings.ListingRepository;
import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.uploads.cloudinary.dto.CloudinarySignatureRequest;
import com.campusplug.api.uploads.cloudinary.dto.CloudinarySignatureResponse;
import com.campusplug.api.users.UserRepository;

import com.campusplug.api.uploads.cloudinary.dto.CloudinarySignatureRequest.UploadContext;

@Service
public class CloudinarySignatureService {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L; // 10 MB

    private final CloudinaryProperties properties;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;

    public CloudinarySignatureService(
            CloudinaryProperties properties,
            ListingRepository listingRepository,
            UserRepository userRepository) {
        this.properties = properties;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
    }

    public CloudinarySignatureResponse createSignature(String email, CloudinarySignatureRequest req) {
        requireConfigured();

        UserRepository.UserProfileProjection user = userRepository.findProfileByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));

        UploadContext context = req.getUploadContext() != null ? req.getUploadContext() : UploadContext.LISTING;

        if (context == UploadContext.AVATAR) {
            return buildAvatarSignature(user.getId(), req);
        }

        // LISTING context — original logic
        if (req.getListingId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_LISTING_ID", "listingId is required for LISTING uploads");
        }

        ListingEntity listing = listingRepository.findById(req.getListingId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Listing not found"));

        if (!user.getId().equals(listing.getOwnerUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the owner can perform this action");
        }

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Listing must be ACTIVE");
        }

        return buildSignature(req);
    }

    private CloudinarySignatureResponse buildAvatarSignature(Long userId, CloudinarySignatureRequest req) {
        String avatarFolder = "campusplug/avatars/" + userId;
        String avatarPublicId = avatarFolder + "/profile";

        CloudinarySignatureRequest avatarRequest = new CloudinarySignatureRequest();
        avatarRequest.setUploadContext(UploadContext.AVATAR);
        avatarRequest.setFolder(avatarFolder);
        avatarRequest.setPublicId(avatarPublicId);
        avatarRequest.setOverwrite(Boolean.TRUE);
        avatarRequest.setTimestamp(req.getTimestamp());

        return buildSignature(avatarRequest);
    }

    private CloudinarySignatureResponse buildSignature(CloudinarySignatureRequest req) {
        Long requestedTimestamp = req.getTimestamp();
        long timestamp = Instant.now().getEpochSecond();
        if (requestedTimestamp != null) {
            timestamp = requestedTimestamp;
        }

        // Only Cloudinary-recognised signable params go into this map.
        // max_file_size is NOT a signable parameter — including it causes a SHA1 mismatch → 401.
        Map<String, String> params = new LinkedHashMap<>();
        if (req.getFolder() != null && !req.getFolder().isBlank()) {
            params.put("folder", req.getFolder().trim());
        }
        if (req.getPublicId() != null && !req.getPublicId().isBlank()) {
            params.put("public_id", req.getPublicId().trim());
        }
        if (req.getOverwrite() != null) {
            params.put("overwrite", String.valueOf(req.getOverwrite()));
        }
        params.put("timestamp", String.valueOf(timestamp));

        String signature = sign(params, properties.getApiSecret());

        // Add max_file_size AFTER signing so it is returned to the client as a hint
        // but is never part of the signed string.
        params.put("max_file_size", String.valueOf(MAX_FILE_SIZE_BYTES));
        return new CloudinarySignatureResponse(
                properties.getCloudName(),
                properties.getApiKey(),
                timestamp,
                signature,
                params
        );
    }

    private void requireConfigured() {
        if (isBlank(properties.getCloudName()) || isBlank(properties.getApiKey()) || isBlank(properties.getApiSecret())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CLOUDINARY_NOT_CONFIGURED", "Cloudinary is not configured");
        }
    }

    // Cloudinary upload signatures are SHA-1 of "key=value&key=value..." + api_secret
    private static String sign(Map<String, String> params, String apiSecret) {
        Map<String, String> sorted = new TreeMap<>(params);
        StringBuilder toSign = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            if (!toSign.isEmpty()) {
                toSign.append('&');
            }
            toSign.append(e.getKey()).append('=').append(e.getValue());
        }
        toSign.append(apiSecret);

        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(toSign.toString().getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
