package com.campusplug.api.messages;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.conversations.ConversationEntity;
import com.campusplug.api.conversations.ConversationRepository;
import com.campusplug.api.listings.ListingEntity;
import com.campusplug.api.listings.ListingRepository;
import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.messages.dto.MessageListResponse;
import com.campusplug.api.messages.dto.MessageResponse;
import com.campusplug.api.presence.PresenceService;
import com.campusplug.api.realtime.ConversationEventsPublisher;
import com.campusplug.api.users.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MessageService {

    private final ConversationRepository conversationRepository;
    private final ListingRepository listingRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConversationEventsPublisher conversationEventsPublisher;
    private final PresenceService presenceService;
    private final ConversationLongPollNotifier longPollNotifier;

    public MessageService(
            ConversationRepository conversationRepository,
            ListingRepository listingRepository,
            MessageRepository messageRepository,
            UserRepository userRepository,
            ConversationEventsPublisher conversationEventsPublisher,
            PresenceService presenceService,
            ConversationLongPollNotifier longPollNotifier) {
        this.conversationRepository = conversationRepository;
        this.listingRepository = listingRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.conversationEventsPublisher = conversationEventsPublisher;
        this.presenceService = presenceService;
        this.longPollNotifier = longPollNotifier;
    }

    @Transactional
    public MessageResponse send(String email, Long conversationId, String body) {
        UserRepository.UserProfileProjection me = requireUser(email);

        ConversationEntity conversation = conversationRepository.findParticipantConversation(conversationId, me.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT", "Only conversation participants can send messages"));

        if (conversation.getListingId() != null) {
            ListingEntity listing = listingRepository.findById(conversation.getListingId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Listing not found"));

            if (listing.getStatus() == ListingStatus.SOLD) {
                throw new ApiException(HttpStatus.CONFLICT, "LISTING_SOLD", "Cannot send messages for SOLD listings");
            }
        }

        MessageEntity msg = new MessageEntity();
        msg.setConversationId(conversationId);
        msg.setSenderUserId(me.getId());
        msg.setBody(body);

        MessageEntity saved = messageRepository.save(msg);
        conversationRepository.touchUpdatedAt(conversationId);

        MessageResponse response = new MessageResponse(
                saved.getId(),
                saved.getConversationId(),
                saved.getSenderUserId(),
                saved.getBody(),
                saved.getCreatedAt()
        );

        presenceService.markActive(me.getId());
        longPollNotifier.notifyNewMessage(conversationId);
        conversationEventsPublisher.publishNewMessage(conversationId, response);

        return response;
    }

    @Transactional(readOnly = true)
    public MessageListResponse longPoll(String email, Long conversationId, Long afterMessageId, int timeoutSeconds) {
        UserRepository.UserProfileProjection me = requireUser(email);

        conversationRepository.findParticipantConversation(conversationId, me.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT", "Only conversation participants can access messages"));

        if (afterMessageId == null || afterMessageId < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "afterMessageId must be >= 0");
        }

        int timeout = Math.min(25, Math.max(1, timeoutSeconds));
        long deadline = System.currentTimeMillis() + timeout * 1000L;

        while (true) {
            List<MessageEntity> items = messageRepository.findNewMessages(
                    conversationId,
                    afterMessageId,
                    PageRequest.of(0, 50)
            );

            if (!items.isEmpty()) {
                return new MessageListResponse(items.stream().map(MessageService::toResponse).toList());
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return new MessageListResponse(List.of());
            }

            try {
                longPollNotifier.await(conversationId, Math.min(1000, remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new MessageListResponse(List.of());
            }
        }
    }

    @Transactional(readOnly = true)
    public MessageListResponse latest(String email, Long conversationId, int limit) {
        UserRepository.UserProfileProjection me = requireUser(email);

        conversationRepository.findParticipantConversation(conversationId, me.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT", "Only conversation participants can access messages"));

        int safeLimit = Math.min(100, Math.max(1, limit));
        List<MessageEntity> latest = messageRepository.findLatestMessages(conversationId, PageRequest.of(0, safeLimit));
        // repository returns desc; reverse for client readability
        List<MessageResponse> items = new ArrayList<>(latest.stream().map(MessageService::toResponse).toList());
        Collections.reverse(items);
        return new MessageListResponse(items);
    }

    private UserRepository.UserProfileProjection requireUser(String email) {
        return userRepository.findProfileByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
    }

    private static MessageResponse toResponse(MessageEntity e) {
        return new MessageResponse(e.getId(), e.getConversationId(), e.getSenderUserId(), e.getBody(), e.getCreatedAt());
    }
}
