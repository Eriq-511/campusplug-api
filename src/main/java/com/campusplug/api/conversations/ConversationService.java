package com.campusplug.api.conversations;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.conversations.dto.ConversationListItemResponse;
import com.campusplug.api.conversations.dto.ConversationPageResponse;
import com.campusplug.api.listings.ListingEntity;
import com.campusplug.api.listings.ListingRepository;
import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.presence.PresenceService;
import com.campusplug.api.users.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final PresenceService presenceService;

    public ConversationService(
            ConversationRepository conversationRepository,
            ListingRepository listingRepository,
            UserRepository userRepository,
            PresenceService presenceService) {
        this.conversationRepository = conversationRepository;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
        this.presenceService = presenceService;
    }

    @Transactional
    public ConversationEntity createOrGet(String email, Long listingId) {
        UserRepository.UserProfileProjection me = requireUser(email);

        ListingEntity listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Listing not found"));

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Listing must be ACTIVE");
        }

        Long sellerUserId = listing.getOwnerUserId();
        Long buyerUserId = me.getId();
        if (buyerUserId.equals(sellerUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_MESSAGE_SELF", "Cannot start a conversation with yourself");
        }

        return conversationRepository
                .findByListingIdAndBuyerUserIdAndSellerUserId(listingId, buyerUserId, sellerUserId)
                .orElseGet(() -> {
                    ConversationEntity c = new ConversationEntity();
                    c.setListingId(listingId);
                    c.setBuyerUserId(buyerUserId);
                    c.setSellerUserId(sellerUserId);
                    return conversationRepository.save(c);
                });
    }

    @Transactional(readOnly = true)
    public ConversationPageResponse list(String email, int page, int size) {
        UserRepository.UserProfileProjection me = requireUser(email);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(50, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<ConversationRepository.ConversationListItemProjection> result =
                conversationRepository.findConversationList(me.getId(), pageable);

        List<ConversationListItemResponse> items = result.getContent().stream().map(p ->
                new ConversationListItemResponse(
                        p.getId(),
                        p.getListingId(),
                        p.getListingTitle(),
                        p.getCounterpartUserId(),
                        p.getCounterpartFullName(),
                        p.getLastMessageBody(),
                        p.getLastMessageAt(),
                        presenceService.isActive(p.getCounterpartUserId())
                )
        ).toList();

        return new ConversationPageResponse(items, safePage, safeSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ConversationEntity requireParticipantConversation(String email, Long conversationId) {
        UserRepository.UserProfileProjection me = requireUser(email);
        return conversationRepository.findParticipantConversation(conversationId, me.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT", "Only conversation participants can access this resource"));
    }

    private UserRepository.UserProfileProjection requireUser(String email) {
        return userRepository.findProfileByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
    }
}
