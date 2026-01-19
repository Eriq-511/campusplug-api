package com.campusplug.api.listings.dto;

import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.listings.images.dto.ListingImageResponse;

import java.time.Instant;
import java.util.List;

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
        Instant createdAt,
        String primaryImageUrl,
        List<ListingImageResponse> images
) {
}
