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
        // Poster's display name — shown directly on the card so browsers see peer identity
        // without needing to open the listing first. Reinforces the P2P model.
        String ownerFullName
) {
}
