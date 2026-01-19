package com.campusplug.api.bookmarks.dto;

import java.util.List;

public record BookmarkPageResponse(
        List<BookmarkCardResponse> items,
        int page,
        int size,
        long total
) {
}
