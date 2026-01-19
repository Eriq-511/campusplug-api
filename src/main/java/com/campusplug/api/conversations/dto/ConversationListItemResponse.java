package com.campusplug.api.conversations.dto;

import java.time.Instant;

public record ConversationListItemResponse(
        Long id,
        Long listingId,
        String listingTitle,
        Long counterpartUserId,
        String counterpartFullName,
        String lastMessageBody,
        Instant lastMessageAt,
        Boolean counterpartActiveNow
) {
}
