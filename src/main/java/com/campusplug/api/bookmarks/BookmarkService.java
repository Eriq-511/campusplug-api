package com.campusplug.api.bookmarks;

import com.campusplug.api.bookmarks.dto.BookmarkCardResponse;
import com.campusplug.api.bookmarks.dto.BookmarkPageResponse;
import com.campusplug.api.common.ApiException;
import com.campusplug.api.listings.ListingEntity;
import com.campusplug.api.listings.ListingRepository;
import com.campusplug.api.listings.ListingStatus;
import com.campusplug.api.listings.images.ListingImageEntity;
import com.campusplug.api.listings.images.ListingImageRepository;
import com.campusplug.api.users.UserEntity;
import com.campusplug.api.users.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;

    public BookmarkService(
            BookmarkRepository bookmarkRepository,
            UserRepository userRepository,
            ListingRepository listingRepository,
            ListingImageRepository listingImageRepository) {
        this.bookmarkRepository = bookmarkRepository;
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.listingImageRepository = listingImageRepository;
    }

    public BookmarkCardResponse add(String userEmail, Long listingId) {
        UserEntity user = getUserOrThrow(userEmail);
        ListingEntity listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "Listing not found"));

        if (listing.getStatus() == ListingStatus.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LISTING_NOT_ACTIVE", "Cannot bookmark a pending listing");
        }

        BookmarkId id = new BookmarkId(user.getId(), listingId);
        if (!bookmarkRepository.existsById(id)) {
            BookmarkEntity entity = new BookmarkEntity();
            entity.setId(id);
            entity.setUser(user);
            entity.setListing(listing);
            bookmarkRepository.save(entity);
        }

        return getBookmark(user.getId(), listingId);
    }

public BookmarkPageResponse list(String userEmail, Double lat, Double lng, int page, int size) {
        UserEntity user = getUserOrThrow(userEmail);
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size));

        Page<BookmarkRepository.BookmarkListingProjection> result = bookmarkRepository.findBookmarks(user.getId(), lat, lng, pageable);
        List<Long> ids = result.getContent().stream().map(BookmarkRepository.BookmarkListingProjection::getListingId).toList();
        Map<Long, String> primaryImages = loadPrimaryImages(ids);

        List<BookmarkCardResponse> items = result.getContent().stream()
                .map(r -> new BookmarkCardResponse(
                        r.getListingId(),
                        r.getTitle(),
                        r.getPriceUgx(),
                        r.getCurrency(),
                        r.getCategoryCode(),
                        r.getLocationText(),
                        r.getCampus(),
                        primaryImages.get(r.getListingId()),
                        r.getListingCreatedAt(),
                        r.getStatus(),
                        r.getBookmarkedAt(),
                        r.getDistanceMeters()
                ))
                .toList();

        return new BookmarkPageResponse(items, pageable.getPageNumber(), pageable.getPageSize(), result.getTotalElements());
    }

    public void remove(String userEmail, Long listingId) {
        UserEntity user = getUserOrThrow(userEmail);
        BookmarkId id = new BookmarkId(user.getId(), listingId);
        if (bookmarkRepository.existsById(id)) {
            bookmarkRepository.deleteById(id);
        }
    }

    private BookmarkCardResponse getBookmark(Long userId, Long listingId) {
        BookmarkRepository.BookmarkListingProjection r = bookmarkRepository.findBookmark(userId, listingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKMARK_NOT_FOUND", "Bookmark not found"));

        Map<Long, String> primaryImages = loadPrimaryImages(List.of(listingId));

        return new BookmarkCardResponse(
                r.getListingId(),
                r.getTitle(),
                r.getPriceUgx(),
                r.getCurrency(),
                r.getCategoryCode(),
                r.getLocationText(),
                r.getCampus(),
                primaryImages.get(listingId),
                r.getListingCreatedAt(),
                r.getStatus(),
                r.getBookmarkedAt(),
                null  // no lat/lng context on add
        );
    }

    private UserEntity getUserOrThrow(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized"));
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
}
