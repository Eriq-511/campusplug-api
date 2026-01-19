package com.campusplug.api.bookmarks.dto;

import java.time.Instant;

public record BookmarkCardResponse(
        Long id,
        String title,
        Long priceUgx,
        String currency,
        String categoryCode,
        String locationText,
        String campus,
        String primaryImageUrl,
        Instant createdAt,
        String status,
        Instant bookmarkedAt
) {
}
