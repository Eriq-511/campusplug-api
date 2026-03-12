package com.campusplug.api.messages.dto;

import java.time.Instant;

public record MessageResponse(
        Long id,
        Long conversationId,
        Long senderUserId,
        String body,
        Long referencedListingId,  // NEW: which listing this message is about (optional)
        Instant createdAt
) {
}
