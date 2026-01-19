package com.campusplug.api.categories.dto;

public record CategoryResponse(
        String code,
        String displayName,
        String coverImageUrl,
        long activeListingCount,
        String badge
) {
}
