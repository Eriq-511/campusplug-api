package com.campusplug.api.messages;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/messages")
public class GlobalMessageController {

    private final MessageService messageService;

    public GlobalMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Endpoint to fetch unread message summary for the authenticated user.
     * Returns the count of conversations with unread messages.
     */
    @GetMapping("/unread-summary")
    public ResponseEntity<Integer> getUnreadSummary(Authentication authentication) {
        int unreadCount = messageService.getUnreadConversationsCount(authentication.getName());
        return ResponseEntity.ok(unreadCount);
    }
}