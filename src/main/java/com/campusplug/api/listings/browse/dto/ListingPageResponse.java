package com.campusplug.api.listings.browse.dto;

import java.util.List;

public record ListingPageResponse(
        List<ListingCardResponse> items,
        int page,
        int size,
        long total
) {
}
