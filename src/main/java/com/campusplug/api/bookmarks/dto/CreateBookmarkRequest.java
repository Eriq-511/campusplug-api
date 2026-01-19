package com.campusplug.api.bookmarks.dto;

import jakarta.validation.constraints.NotNull;

public record CreateBookmarkRequest(
        @NotNull Long listingId
) {
}
