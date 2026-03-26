package com.campusplug.api.listings.browse;

import com.campusplug.api.cache.CacheConfig;
import com.campusplug.api.common.ApiException;
import com.campusplug.api.listings.ListingRepository;
import com.campusplug.api.listings.images.ListingImageEntity;
import com.campusplug.api.listings.images.ListingImageRepository;
import com.campusplug.api.listings.browse.dto.ListingCardResponse;
import com.campusplug.api.listings.browse.dto.ListingPageResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ListingBrowseService {

    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final com.campusplug.api.users.UserRepository userRepository;

    public ListingBrowseService(ListingRepository listingRepository, ListingImageRepository listingImageRepository, com.campusplug.api.users.UserRepository userRepository) {
        this.listingRepository = listingRepository;
        this.listingImageRepository = listingImageRepository;
        this.userRepository = userRepository;
    }

    @Cacheable(cacheNames = CacheConfig.SEARCH_CACHE, keyGenerator = "searchKeyGenerator")
    public ListingPageResponse search(
            String query,
            String categoryCode,
            String campus,
            Long minPriceUgx,
            Long maxPriceUgx,
            int page,
            int size) {

        String q = normalizeQuery(query);
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size));

        Page<ListingRepository.ListingCardProjection> result = listingRepository.searchActive(
                q,
                trimToNull(categoryCode),
                normalizeCampus(campus),
                minPriceUgx,
                maxPriceUgx,
                pageable
        );

        return toPageResponse(result, pageable);
    }

    @Cacheable(cacheNames = CacheConfig.NEARBY_CACHE, keyGenerator = "nearbyKeyGenerator")
    public ListingPageResponse nearby(
            double lat,
            double lng,
            double radiusKm,
            String categoryCode,
            String campus,
            int page,
            int size) {

        if (radiusKm <= 0 || radiusKm > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RADIUS", "radiusKm must be between 0 and 200");
        }

        double radiusMeters = radiusKm * 1000.0;
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size));

        Page<ListingRepository.ListingCardProjection> result = listingRepository.nearbyActive(
                lat,
                lng,
                radiusMeters,
                trimToNull(categoryCode),
                normalizeCampus(campus),
                pageable
        );

        return toPageResponse(result, pageable);
    }

    /** G9 — listings in a specific zone sorted by proximity */
    public ListingPageResponse byZone(
            String zoneTag,
            double lat,
            double lng,
            int page,
            int size) {
        String normalizedZoneTag = normalizeZoneTag(zoneTag);
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size));
        Page<ListingRepository.ListingCardProjection> result =
            listingRepository.findByZoneTagActive(normalizedZoneTag, lat, lng, pageable);
        return toPageResponse(result, pageable);
    }

    /** G9 — fast count for notification badge */
    public long countByZone(String zoneTag) {
        return listingRepository.countByZoneTagAndStatus(normalizeZoneTag(zoneTag), "ACTIVE");
    }

    /** G9 — global distance-sorted feed */
    public ListingPageResponse allByDistance(
            double lat,
            double lng,
            int page,
            int size) {
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size));
        Page<ListingRepository.ListingCardProjection> result =
                listingRepository.findAllActiveByDistance(lat, lng, pageable);
        return toPageResponse(result, pageable);
    }

    /** Get single listing detail by ID (product card detail view) */
    public com.campusplug.api.listings.dto.ListingResponse getById(Long id) {
        com.campusplug.api.listings.ListingEntity listing = listingRepository.findById(id)
            .orElseThrow(() -> new ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, 
                "NOT_FOUND", 
                "Listing not found"
            ));

        // Only allow viewing ACTIVE listings
        if (!com.campusplug.api.listings.ListingStatus.ACTIVE.equals(listing.getStatus())) {
            throw new ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "Listing not found"
            );
        }

        // Load images for this listing
        List<com.campusplug.api.listings.images.ListingImageEntity> imageEntities =
                listingImageRepository.findByListingIdOrderByCreatedAtAsc(id);
        List<com.campusplug.api.listings.images.dto.ListingImageResponse> images = imageEntities.stream()
                .map(e -> new com.campusplug.api.listings.images.dto.ListingImageResponse(
                        e.getId(),
                        e.getPublicId(),
                        e.getSecureUrl(),
                        e.getWidth(),
                        e.getHeight(),
                        e.getBytes(),
                        e.getFormat(),
                        e.getCreatedAt()
                ))
                .toList();

        // Convert to response
        List<com.campusplug.api.listings.images.dto.ListingImageResponse> safeImages = images.isEmpty() ? List.of() : images;
        String primary = safeImages.isEmpty() ? null : safeImages.get(0).secureUrl();
        com.campusplug.api.listings.dto.ListingActions actions = com.campusplug.api.listings.dto.ListingActions.forActive();

        return new com.campusplug.api.listings.dto.ListingResponse(
                listing.getId(),
                listing.getOwnerUserId(),
                listing.getTitle(),
                listing.getPriceUgx(),
                listing.getCurrency(),
                listing.getCategoryCode(),
                listing.getDescription(),
                listing.getLocationText(),
                listing.getCampus(),
                listing.getStatus(),
                actions,
                listing.getCreatedAt(),
                primary,
                safeImages
        );
    }

    private ListingPageResponse toPageResponse(Page<ListingRepository.ListingCardProjection> page, Pageable pageable) {
        List<Long> ids = page.getContent().stream()
            .map(ListingRepository.ListingCardProjection::getId)
            .collect(Collectors.toList());
        Map<Long, String> primaryImageByListingId = loadPrimaryImages(ids);

        // Collect all owner user IDs
        List<Long> ownerUserIds = page.getContent().stream()
            .map(ListingRepository.ListingCardProjection::getOwnerUserId)
            .distinct()
            .collect(Collectors.toList());
        Map<Long, String> ownerAvatarById = new HashMap<>();
        for (Long userId : ownerUserIds) {
            userRepository.findPublicById(userId).ifPresent(u -> ownerAvatarById.put(userId, u.getAvatarUrl()));
        }

        List<ListingCardResponse> items = page.getContent().stream()
                .map(r -> new ListingCardResponse(
                        r.getId(),
                        r.getTitle(),
                        r.getPriceUgx(),
                        r.getCurrency(),
                        r.getCategoryCode(),
                        r.getLocationText(),
                        r.getCampus(),
                        primaryImageByListingId.get(r.getId()),
                        r.getCreatedAt(),
                        r.getDistanceMeters(),
                        r.getOwnerFullName(),
                        ownerAvatarById.get(r.getOwnerUserId())
                ))
                    .collect(Collectors.toList());

        return new ListingPageResponse(items, pageable.getPageNumber(), pageable.getPageSize(), page.getTotalElements());
    }

    private Map<Long, String> loadPrimaryImages(List<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return Map.of();
        }
        List<ListingImageEntity> entities = listingImageRepository.findByListingIdInOrderByListingIdAscCreatedAtAsc(listingIds);
        Map<Long, String> primary = new HashMap<>();
        for (ListingImageEntity e : entities) {
            primary.putIfAbsent(e.getListingId(), e.getSecureUrl());
        }
        return primary;
    }

    private static int clampPage(int page) {
        return Math.max(0, page);
    }

    private static int clampSize(int size) {
        int s = size <= 0 ? 20 : size;
        return Math.min(s, 100);
    }

    private static String normalizeQuery(String query) {
        String q = trimToNull(query);
        if (q == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "query must not be blank");
        }
        return q;
    }

    private static String normalizeCampus(String campus) {
        if (campus == null) {
            return null;
        }
        String trimmed = campus.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private static String normalizeZoneTag(String zoneTag) {
        String normalized = trimToNull(zoneTag);
        if (normalized == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "zoneTag must not be blank");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
