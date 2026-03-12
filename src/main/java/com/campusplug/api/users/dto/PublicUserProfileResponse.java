package com.campusplug.api.users.dto;

import java.time.Instant;

/**
 * Publicly visible subset of a user profile, safe to expose to any
 * authenticated student.  Never includes credentials, FCM tokens, or
 * raw geo coordinates.
 *
 * Exposed via GET /api/v1/users/{id}/public so a student browsing a listing
 * can see who posted it without starting a conversation first.
 */
public record PublicUserProfileResponse(
        Long id,
        String fullName,
        String campus,
        String avatarUrl,
        long activeListingsCount,
        Instant memberSince
) {
}
