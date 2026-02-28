package com.campusplug.api.bookmarks;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<BookmarkEntity, BookmarkId> {

    interface BookmarkListingProjection {
        Long getListingId();

        String getTitle();

        Long getPriceUgx();

        String getCurrency();

        String getCategoryCode();

        String getLocationText();

        String getCampus();

        String getStatus();

        Instant getListingCreatedAt();

        Instant getBookmarkedAt();

        /** Metres from the caller's position to the listing geo-point; null when no location supplied. */
        Double getDistanceMeters();
    }

    @Query(
            value = """
                select
                  b.listing_id as listingId,
                  l.title as title,
                  l.price_ugx as priceUgx,
                  l.currency as currency,
                  l.category_code as categoryCode,
                  l.location_text as locationText,
                  l.campus as campus,
                  l.status::text as status,
                  l.created_at as listingCreatedAt,
                  b.created_at as bookmarkedAt,
                  case
                    when cast(:lat as float8) is not null
                     and cast(:lng as float8) is not null
                     and l.geo is not null
                    then ST_Distance(
                           l.geo::geography,
                           ST_SetSRID(ST_MakePoint(cast(:lng as float8), cast(:lat as float8)), 4326)::geography
                         )
                    else null
                  end as distanceMeters
                from bookmarks b
                join listings l on l.id = b.listing_id
                where b.user_id = :userId
                order by b.created_at desc
                """,
            countQuery = """
                select count(*)
                from bookmarks b
                where b.user_id = :userId
                """,
            nativeQuery = true
    )
    Page<BookmarkListingProjection> findBookmarks(
            @Param("userId") Long userId,
            @Param("lat") Double lat,
            @Param("lng") Double lng,
            Pageable pageable);

    @Query(
            value = """
                select
                  b.listing_id as listingId,
                  l.title as title,
                  l.price_ugx as priceUgx,
                  l.currency as currency,
                  l.category_code as categoryCode,
                  l.location_text as locationText,
                  l.campus as campus,
                  l.status::text as status,
                  l.created_at as listingCreatedAt,
                  b.created_at as bookmarkedAt,
                  null::float8 as distanceMeters
                from bookmarks b
                join listings l on l.id = b.listing_id
                where b.user_id = :userId and b.listing_id = :listingId
                """,
            nativeQuery = true
    )
    Optional<BookmarkListingProjection> findBookmark(@Param("userId") Long userId, @Param("listingId") Long listingId);
}
