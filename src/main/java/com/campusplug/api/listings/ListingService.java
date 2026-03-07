package com.campusplug.api.listings;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campusplug.api.cache.CacheEvictionService;
import com.campusplug.api.common.ApiException;
import com.campusplug.api.listings.dto.CreateListingRequest;
import com.campusplug.api.listings.dto.ListingActions;
import com.campusplug.api.listings.dto.ListingResponse;
import com.campusplug.api.listings.dto.MyListingsResponse;
import com.campusplug.api.listings.dto.UpdateListingRequest;
import com.campusplug.api.listings.images.ListingImageEntity;
import com.campusplug.api.listings.images.ListingImageRepository;
import com.campusplug.api.listings.images.dto.AttachListingImageRequest;
import com.campusplug.api.listings.images.dto.ListingImageResponse;
import com.campusplug.api.realtime.ListingsRealtimePublisher;
import com.campusplug.api.realtime.PushNotificationService;
import com.campusplug.api.users.UserRepository;

@Service
public class ListingService {

    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ListingImageRepository listingImageRepository;
    private final CacheEvictionService cacheEvictionService;
    private final ListingsRealtimePublisher listingsRealtimePublisher;
    private final PushNotificationService pushNotificationService;

    public ListingService(
            ListingRepository listingRepository,
            UserRepository userRepository,
            ListingImageRepository listingImageRepository,
            CacheEvictionService cacheEvictionService,
            ListingsRealtimePublisher listingsRealtimePublisher,
            PushNotificationService pushNotificationService) {
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
        this.listingImageRepository = listingImageRepository;
        this.cacheEvictionService = cacheEvictionService;
        this.listingsRealtimePublisher = listingsRealtimePublisher;
        this.pushNotificationService = pushNotificationService;
    }

    @Transactional
    public ListingResponse create(String email, CreateListingRequest req) {
        UserRepository.UserProfileProjection user = requireUser(email);

        String title = req.getTitle().trim();
        if (title.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "title must not be blank");
        }

        long price = req.getPriceUgx();
        if (price < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE", "priceUgx must be >= 0");
        }

        String category = req.getCategoryCode().trim();
        if (category.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "categoryCode must not be blank");
        }

        boolean hasExplicitLocationField = req.getLocationText() != null
            || req.getLat() != null
            || req.getLng() != null
            || req.getCampus() != null;

        boolean hasRegisteredLocation = user.getRegisteredLat() != null
                && user.getRegisteredLng() != null
                && !isBlank(user.getRegisteredLocationText());

        boolean hasAlternateLocation = user.getAlternateLat() != null
                && user.getAlternateLng() != null
                && !isBlank(user.getAlternateLocationText());

        LocationSource source;
        if (Boolean.TRUE.equals(req.getUseRegisteredLocation())) {
            source = LocationSource.REGISTERED;
        } else if (hasExplicitLocationField) {
            source = LocationSource.EXPLICIT;
        } else if (hasRegisteredLocation) {
            source = LocationSource.REGISTERED;
        } else if (hasAlternateLocation) {
            source = LocationSource.ALTERNATE;
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAVED_LOCATION_MISSING", "User has no saved location");
        }

        LocationResolved loc = resolveLocation(
                user,
                source,
                req.getLocationText(),
                req.getLat(),
                req.getLng(),
                req.getCampus()
        );

        ListingEntity listing = new ListingEntity();
        listing.setOwnerUserId(user.getId());
        listing.setTitle(title);
        listing.setPriceUgx(price);
        listing.setCurrency("UGX");
        listing.setCategoryCode(category);
        listing.setDescription(trimToNull(req.getDescription()));
        listing.setLocationText(loc.locationText());
        listing.setCampus(loc.campus());
        listing.setStatus(ListingStatus.PENDING);

        ListingEntity saved = listingRepository.save(listing);

        if (loc.lat() != null && loc.lng() != null) {
            listingRepository.updateGeo(saved.getId(), loc.lat(), loc.lng());
            // G9 — auto-tag listing with zone polygon it falls inside (fire-and-forget if no zone)
            // clearAutomatically=true on the repo method evicts the entity; reload to pick up zone_tag
            listingRepository.autoTagZone(saved.getId(), loc.lat(), loc.lng());
            saved = listingRepository.findById(saved.getId()).orElse(saved);
        } else {
            listingRepository.clearGeo(saved.getId());
        }

        saved.setStatus(ListingStatus.ACTIVE);
        ListingEntity active = listingRepository.save(saved);
        cacheEvictionService.evictListingsReadCaches();
        listingsRealtimePublisher.publishListingNew(active);
        // G4 — async FCM push to nearby users (non-blocking)
        if (loc.lat() != null && loc.lng() != null) {
            pushNotificationService.notifyNearbyUsersWithCoords(active, loc.lat(), loc.lng());
        }
        return toResponse(active, List.of());
    }

    public MyListingsResponse myListings(String email, String statusParam) {
        UserRepository.UserProfileProjection user = requireUser(email);

        List<ListingEntity> items;
        if (statusParam == null || statusParam.isBlank() || statusParam.equalsIgnoreCase("ALL")) {
            items = listingRepository.findByOwnerUserId(user.getId(), ListingRepository.MY_LISTINGS_SORT);
        } else {
            ListingStatus status;
            try {
                status = ListingStatus.valueOf(statusParam.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unknown status: " + statusParam);
            }
            items = listingRepository.findByOwnerUserIdAndStatus(user.getId(), status, ListingRepository.MY_LISTINGS_SORT);
        }

        Map<Long, List<ListingImageResponse>> imagesByListingId = loadImagesByListingId(items.stream().map(ListingEntity::getId).toList());
        return new MyListingsResponse(items.stream().map(e -> toResponse(e, imagesByListingId.get(e.getId()))).toList());
    }

    @Transactional
    public ListingResponse update(String email, Long listingId, UpdateListingRequest req) {
        UserRepository.UserProfileProjection user = requireUser(email);

        ListingEntity listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Listing not found"));

        requireOwner(user.getId(), listing);
        requireStatus(listing, ListingStatus.ACTIVE);

        if (req.getTitle() != null) {
            String title = req.getTitle().trim();
            if (title.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "title must not be blank");
            }
            listing.setTitle(title);
        }

        if (req.getPriceUgx() != null) {
            long price = req.getPriceUgx();
            if (price < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE", "priceUgx must be >= 0");
            }
            listing.setPriceUgx(price);
        }

        if (req.getCategoryCode() != null) {
            String category = req.getCategoryCode().trim();
            if (category.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "categoryCode must not be blank");
            }
            listing.setCategoryCode(category);
        }

        if (req.getDescription() != null) {
            listing.setDescription(trimToNull(req.getDescription()));
        }

        boolean hasAnyLocationField = req.getUseRegisteredLocation() != null
                || req.getLocationText() != null
                || req.getLat() != null
                || req.getLng() != null
                || req.getCampus() != null;

        if (hasAnyLocationField) {
                LocationSource source = req.getUseRegisteredLocation() != null && req.getUseRegisteredLocation()
                    ? LocationSource.REGISTERED
                    : LocationSource.EXPLICIT;
            LocationResolved loc = resolveLocation(
                    user,
                    source,
                    req.getLocationText(),
                    req.getLat(),
                    req.getLng(),
                    req.getCampus()
            );

            listing.setLocationText(loc.locationText());
            listing.setCampus(loc.campus());

            if (loc.lat() != null && loc.lng() != null) {
                listingRepository.updateGeo(listing.getId(), loc.lat(), loc.lng());
                listingRepository.autoTagZone(listing.getId(), loc.lat(), loc.lng());
            } else {
                listingRepository.clearGeo(listing.getId());
            }
        }

        ListingEntity saved = listingRepository.save(listing);
        cacheEvictionService.evictListingsReadCaches();
        return toResponse(saved, listImages(saved.getId()));
    }

    @Transactional
    public ListingResponse softDelete(String email, Long listingId) {
        UserRepository.UserProfileProjection user = requireUser(email);
        ListingEntity listing = getOwnedListing(user.getId(), listingId);
        requireStatus(listing, ListingStatus.ACTIVE);

        listing.setStatus(ListingStatus.DELETED);
        ListingEntity saved = listingRepository.save(listing);
        cacheEvictionService.evictListingsReadCaches();
        return toResponse(saved, listImages(saved.getId()));
    }

    @Transactional
    public ListingResponse restore(String email, Long listingId) {
        UserRepository.UserProfileProjection user = requireUser(email);
        ListingEntity listing = getOwnedListing(user.getId(), listingId);
        requireStatus(listing, ListingStatus.DELETED);

        listing.setStatus(ListingStatus.ACTIVE);
        ListingEntity saved = listingRepository.save(listing);
        cacheEvictionService.evictListingsReadCaches();
        return toResponse(saved, listImages(saved.getId()));
    }

    @Transactional
    public ListingResponse markSold(String email, Long listingId) {
        UserRepository.UserProfileProjection user = requireUser(email);
        ListingEntity listing = getOwnedListing(user.getId(), listingId);
        requireStatus(listing, ListingStatus.ACTIVE);

        listing.setStatus(ListingStatus.SOLD);
        ListingEntity saved = listingRepository.save(listing);
        cacheEvictionService.evictListingsReadCaches();
        return toResponse(saved, listImages(saved.getId()));
    }

    @Transactional
    public ListingResponse attachImage(String email, Long listingId, AttachListingImageRequest req) {
        UserRepository.UserProfileProjection user = requireUser(email);
        ListingEntity listing = getOwnedListing(user.getId(), listingId);
        requireStatus(listing, ListingStatus.ACTIVE);

        long count = listingImageRepository.countByListingId(listingId);
        if (count >= 10) {
            throw new ApiException(HttpStatus.CONFLICT, "IMAGE_LIMIT_EXCEEDED", "A listing can have at most 10 images");
        }

        ListingImageEntity image = new ListingImageEntity();
        image.setListingId(listingId);
        image.setPublicId(req.getPublicId().trim());
        image.setSecureUrl(req.getSecureUrl().trim());
        image.setWidth(req.getWidth());
        image.setHeight(req.getHeight());
        image.setBytes(req.getBytes());
        image.setFormat(trimToNull(req.getFormat()));

        try {
            listingImageRepository.save(image);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_IMAGE", "Image already attached to this listing");
        }

        cacheEvictionService.evictListingsReadCaches();

        return toResponse(listing, listImages(listingId));
    }

    @Transactional
    public ListingResponse removeImage(String email, Long listingId, Long imageId) {
        UserRepository.UserProfileProjection user = requireUser(email);
        ListingEntity listing = getOwnedListing(user.getId(), listingId);
        requireStatus(listing, ListingStatus.ACTIVE);

        ListingImageEntity image = listingImageRepository.findById(imageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Image not found"));
        if (!listingId.equals(image.getListingId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Image not found");
        }

        listingImageRepository.delete(image);
        cacheEvictionService.evictListingsReadCaches();
        return toResponse(listing, listImages(listingId));
    }

    @Transactional
    public void purge(String email, Long listingId) {
        UserRepository.UserProfileProjection user = requireUser(email);
        ListingEntity listing = getOwnedListing(user.getId(), listingId);
        requireStatus(listing, ListingStatus.DELETED);

        listingRepository.delete(listing);
        cacheEvictionService.evictListingsReadCaches();
    }

    private ListingEntity getOwnedListing(Long userId, Long listingId) {
        ListingEntity listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Listing not found"));
        requireOwner(userId, listing);
        return listing;
    }

    private static void requireOwner(Long userId, ListingEntity listing) {
        if (!userId.equals(listing.getOwnerUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the owner can perform this action");
        }
    }

    private static void requireStatus(ListingEntity listing, ListingStatus required) {
        if (listing.getStatus() != required) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Listing must be " + required);
        }
    }

    private UserRepository.UserProfileProjection requireUser(String email) {
        return userRepository.findProfileByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));
    }

        private static LocationResolved resolveLocation(
            UserRepository.UserProfileProjection user,
            LocationSource source,
            String locationText,
            Double lat,
            Double lng,
            String campus) {

        String resolvedCampus = normalizeCampus(campus);
        if (resolvedCampus == null) {
            resolvedCampus = normalizeCampus(user.getCampus());
        }

        if (source == LocationSource.REGISTERED) {
            if (user.getRegisteredLat() == null || user.getRegisteredLng() == null || isBlank(user.getRegisteredLocationText())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REGISTERED_LOCATION_MISSING", "User has no registered location");
            }

            String label = user.getRegisteredLocationText().trim();
            Double rLat = user.getRegisteredLat();
            Double rLng = user.getRegisteredLng();
            return new LocationResolved(label, rLat, rLng, resolvedCampus);
        }

        if (source == LocationSource.ALTERNATE) {
            if (user.getAlternateLat() == null || user.getAlternateLng() == null || isBlank(user.getAlternateLocationText())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ALTERNATE_LOCATION_MISSING", "User has no alternate location");
            }

            String label = user.getAlternateLocationText().trim();
            Double aLat = user.getAlternateLat();
            Double aLng = user.getAlternateLng();
            return new LocationResolved(label, aLat, aLng, resolvedCampus);
        }

        if ((lat == null) != (lng == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "lat and lng must be provided together");
        }

        String locText = trimToNull(locationText);
        return new LocationResolved(locText, lat, lng, resolvedCampus);
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ListingResponse toResponse(ListingEntity e, List<ListingImageResponse> images) {
        List<ListingImageResponse> safeImages = images == null ? List.of() : images;
        String primary = safeImages.isEmpty() ? null : safeImages.get(0).secureUrl();

        ListingActions actions = switch (e.getStatus()) {
            case ACTIVE  -> ListingActions.forActive();
            case SOLD    -> ListingActions.forSold();
            case DELETED -> ListingActions.forDeleted();
            default      -> ListingActions.forPending();
        };

        return new ListingResponse(
                e.getId(),
                e.getOwnerUserId(),
                e.getTitle(),
                e.getPriceUgx(),
                e.getCurrency(),
                e.getCategoryCode(),
                e.getDescription(),
                e.getLocationText(),
                e.getCampus(),
                e.getStatus(),
                actions,
                e.getCreatedAt(),
                primary,
                safeImages
        );
    }

    private Map<Long, List<ListingImageResponse>> loadImagesByListingId(List<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return Map.of();
        }

        List<ListingImageEntity> entities = listingImageRepository.findByListingIdInOrderByListingIdAscCreatedAtAsc(listingIds);
        Map<Long, List<ListingImageResponse>> out = new HashMap<>();
        for (ListingImageEntity e : entities) {
            out.computeIfAbsent(e.getListingId(), ignored -> new java.util.ArrayList<>()).add(toImageResponse(e));
        }
        return out;
    }

    private List<ListingImageResponse> listImages(Long listingId) {
        return listingImageRepository.findByListingIdOrderByCreatedAtAsc(listingId)
                .stream()
                .map(ListingService::toImageResponse)
                .toList();
    }

    private static ListingImageResponse toImageResponse(ListingImageEntity e) {
        return new ListingImageResponse(
                e.getId(),
                e.getPublicId(),
                e.getSecureUrl(),
                e.getWidth(),
                e.getHeight(),
                e.getBytes(),
                e.getFormat(),
                e.getCreatedAt()
        );
    }

    private record LocationResolved(String locationText, Double lat, Double lng, String campus) {
    }

    private enum LocationSource {
        REGISTERED,
        ALTERNATE,
        EXPLICIT
    }
}
