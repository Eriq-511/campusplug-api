package com.campusplug.api.conversations.dto;

import jakarta.validation.constraints.NotNull;

public record CreateConversationRequest(
        @NotNull Long listingId
) {
}
