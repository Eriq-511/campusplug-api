package com.campusplug.api.listings.browse.dto;

import java.time.Instant;

public record ListingCardResponse(
        Long id,
        String title,
        Long priceUgx,
        String currency,
        String categoryCode,
        String locationText,
        String campus,
        String primaryImageUrl,
        Instant createdAt,
        Double distanceMeters,
        String ownerFullName,
        String ownerAvatarUrl
) {
}
