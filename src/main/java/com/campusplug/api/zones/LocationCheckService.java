package com.campusplug.api.zones;

import com.campusplug.api.listings.ListingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class LocationCheckService {

    private final ZoneRepository zoneRepository;
    private final UserZoneRepository userZoneRepository;
    private final ListingRepository listingRepository;

    public LocationCheckService(ZoneRepository zoneRepository,
                                UserZoneRepository userZoneRepository,
                                ListingRepository listingRepository) {
        this.zoneRepository = zoneRepository;
        this.userZoneRepository = userZoneRepository;
        this.listingRepository = listingRepository;
    }

    @Transactional
    public LocationCheckResponse checkZone(Long userId, double lat, double lng) {
        // 1. Detect zone — exact polygon match first, then 300-m buffer
        ZoneRepository.ZoneProjection zone = zoneRepository.findZoneContaining(lat, lng)
                .orElse(null);
        String accessType = "restricted";
        if (zone != null) {
            accessType = "full";
        } else {
            zone = zoneRepository.findBufferZone(lat, lng).orElse(null);
            if (zone != null) accessType = "buffer";
        }

        // 2. Read previous zone tag before overwriting
        String previousZoneTag = userZoneRepository.findByUserId(userId)
                .map(UserZoneEntity::getZoneTag)
                .orElse(null);

        // 3. Persist updated zone (only when inside a zone)
        if (zone != null) {
            UserZoneEntity uz = userZoneRepository.findByUserId(userId)
                    .orElse(new UserZoneEntity(userId));
            uz.setZoneTag(zone.getTag());
            uz.setUpdatedAt(Instant.now());
            userZoneRepository.save(uz);
        }

        // 4. Count active listings in zone
        long listingCount = (zone != null)
                ? listingRepository.countByZoneTagAndStatus(zone.getTag(), "ACTIVE")
                : 0L;

        return new LocationCheckResponse(
                zone != null ? zone.getName() : null,
                zone != null ? zone.getTag() : null,
                accessType,
                listingCount,
                previousZoneTag
        );
    }
}
