package com.campusplug.api.realtime;

import com.campusplug.api.messages.dto.MessageResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ConversationEventsPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public ConversationEventsPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishNewMessage(Long conversationId, MessageResponse message) {
        messagingTemplate.convertAndSend("/topic/conversations." + conversationId, message);
    }
}
