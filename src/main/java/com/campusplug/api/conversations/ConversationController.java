package com.campusplug.api.conversations;

import com.campusplug.api.conversations.dto.ConversationPageResponse;
import com.campusplug.api.conversations.dto.ConversationResponse;
import com.campusplug.api.conversations.dto.CreateConversationRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/conversations", "/conversations"})
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ConversationResponse create(Authentication authentication, @Valid @RequestBody CreateConversationRequest request) {
        return conversationService.createOrGet(authentication.getName(), request.listingId());
    }

    @GetMapping
    public ConversationPageResponse list(
            Authentication authentication,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size
    ) {
        return conversationService.list(authentication.getName(), page, size);
    }
}
