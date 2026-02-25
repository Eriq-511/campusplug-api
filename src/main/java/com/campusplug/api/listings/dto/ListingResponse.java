package com.campusplug.api.listings.dto;

import java.time.Instant;
import java.util.List;

import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.listings.images.dto.ListingImageResponse;

public record ListingResponse(
        Long id,
        Long ownerUserId,
        String title,
        Long priceUgx,
        String currency,
        String categoryCode,
        String description,
        String locationText,
        String campus,
        ListingStatus status,
        ListingActions actions,
        Instant createdAt,
        String primaryImageUrl,
        List<ListingImageResponse> images
) {
}
