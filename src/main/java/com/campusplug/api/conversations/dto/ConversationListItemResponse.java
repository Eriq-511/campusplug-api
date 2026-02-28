package com.campusplug.api.conversations.dto;

import java.time.Instant;

public record ConversationListItemResponse(
        Long id,
        Long listingId,
        String listingTitle,
        Long counterpartUserId,
        String counterpartFullName,
        String counterpartEmail,
        String counterpartPhoneNumber,
        String counterpartLocationText,
        String lastMessageBody,
        Instant lastMessageAt,
        Boolean counterpartActiveNow
) {
}
