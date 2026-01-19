package com.campusplug.api.listings.dto;

import java.util.List;

public record MyListingsResponse(List<ListingResponse> items) {
}
