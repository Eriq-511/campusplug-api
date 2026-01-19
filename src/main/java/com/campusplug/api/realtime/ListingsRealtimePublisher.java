package com.campusplug.api.realtime;

import com.campusplug.api.listings.ListingEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ListingsRealtimePublisher {

    public record ListingNewEvent(
            Long id,
            String title,
            Long priceUgx,
            String currency,
            String categoryCode,
            String campus,
            Instant createdAt
    ) {
    }

    private final SimpMessagingTemplate messagingTemplate;

    public ListingsRealtimePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishListingNew(ListingEntity listing) {
        if (listing == null) {
            return;
        }
        ListingNewEvent event = new ListingNewEvent(
                listing.getId(),
                listing.getTitle(),
                listing.getPriceUgx(),
                listing.getCurrency(),
                listing.getCategoryCode(),
                listing.getCampus(),
                listing.getCreatedAt()
        );
        messagingTemplate.convertAndSend("/topic/listings.new", event);
    }
}
