package com.campusplug.api.messages.dto;

import java.util.List;

public record MessageListResponse(
        List<MessageResponse> items
) {
}
