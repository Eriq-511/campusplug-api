package com.campusplug.api.listings.images.dto;

import java.time.Instant;

public record ListingImageResponse(
        Long id,
        String publicId,
        String secureUrl,
        Integer width,
        Integer height,
        Long bytes,
        String format,
        Instant createdAt
) {
}
