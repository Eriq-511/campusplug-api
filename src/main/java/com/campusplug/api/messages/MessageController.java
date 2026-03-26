package com.campusplug.api.messages;

import com.campusplug.api.messages.dto.MessageListResponse;
import com.campusplug.api.messages.dto.MessageResponse;
import com.campusplug.api.messages.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping({"/api/v1/conversations/{conversationId}/messages", "/conversations/{conversationId}/messages"})
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
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

    /**
     * Endpoint to mark all messages in a conversation as read for the user.
     */
    @PatchMapping("/mark-as-read")
    public ResponseEntity<Void> markAsRead(Authentication authentication, @PathVariable("conversationId") Long conversationId) {
        messageService.markAsRead(authentication.getName(), conversationId);
        return ResponseEntity.ok().build();
    }

    /**
     * Optional: Heartbeat endpoint for presence (to keep user online status active).
     */
    @PostMapping("/presence/heartbeat")
    public ResponseEntity<Void> heartbeat(Authentication authentication) {
        messageService.heartbeat(authentication.getName());
        return ResponseEntity.ok().build();
    }

    @PostMapping
    public MessageResponse send(
            Authentication authentication,
            @PathVariable("conversationId") Long conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        return messageService.send(authentication.getName(), conversationId, request.body(), request.referencedListingId());
    }

    @GetMapping("/long-poll")
    public MessageListResponse longPoll(
            Authentication authentication,
            @PathVariable("conversationId") Long conversationId,
            @RequestParam(name = "afterMessageId") Long afterMessageId,
            @RequestParam(name = "timeoutSeconds", required = false, defaultValue = "25") int timeoutSeconds) {
        return messageService.longPoll(authentication.getName(), conversationId, afterMessageId, timeoutSeconds);
    }

    @GetMapping
    public MessageListResponse latest(
            Authentication authentication,
            @PathVariable("conversationId") Long conversationId,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        return messageService.latest(authentication.getName(), conversationId, limit);
    }
}
