package com.campusplug.api.conversations.dto;

import java.time.Instant;

/**
 * API response for a single conversation.
 *
 * Field naming is intentionally role-neutral:
 *   inquirerUserId — the student who initiated the chat about the listing
 *   posterUserId   — the student who posted the listing
 *
 * Neither label is permanent. The same user ID can appear as inquirerUserId in
 * one conversation and posterUserId in another within the same session.
 */
public record ConversationResponse(
        Long id,
        Long listingId,
        String listingTitle,
        Long inquirerUserId,
        Long posterUserId,
        Instant createdAt,
        Instant updatedAt
) {
}
