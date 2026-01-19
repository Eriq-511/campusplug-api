package com.campusplug.api.conversations.dto;

import java.util.List;

public record ConversationPageResponse(
        List<ConversationListItemResponse> items,
        int page,
        int size,
        long total
) {
}
