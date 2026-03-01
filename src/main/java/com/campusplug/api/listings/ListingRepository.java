package com.campusplug.api.listings;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ListingRepository extends JpaRepository<ListingEntity, Long> {

    Sort MY_LISTINGS_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    List<ListingEntity> findByOwnerUserId(Long ownerUserId, Sort sort);

    List<ListingEntity> findByOwnerUserIdAndStatus(Long ownerUserId, ListingStatus status, Sort sort);

        interface CategoryCountProjection {
        String getCategoryCode();

        long getActiveCount();
        }

        @Query(value = """
            select category_code as categoryCode,
               count(*) as activeCount
            from listings
            where status = 'ACTIVE'
            group by category_code
            """, nativeQuery = true)
        List<CategoryCountProjection> countActiveByCategory();

        interface ListingCardProjection {
        Long getId();

        Long getOwnerUserId();

        String getTitle();

        Long getPriceUgx();

        String getCurrency();

        String getCategoryCode();

        String getLocationText();

        String getCampus();

        String getStatus();

        Instant getCreatedAt();

        Double getDistanceMeters();
        }

        @Query(
            value = """
                select l.id as id,
                   l.owner_user_id as ownerUserId,
                   l.title as title,
                   l.price_ugx as priceUgx,
                   l.currency as currency,
                   l.category_code as categoryCode,
                   l.location_text as locationText,
                   l.campus as campus,
                   l.status::text as status,
                   l.created_at as createdAt,
                   null::double precision as distanceMeters
                from listings l
                where l.status = 'ACTIVE'
                  and l.search_tsv @@ plainto_tsquery('simple', :query)
                  and (:categoryCode is null or l.category_code = :categoryCode)
                  and (:campus is null or l.campus = :campus)
                  and (:minPriceUgx is null or l.price_ugx >= :minPriceUgx)
                  and (:maxPriceUgx is null or l.price_ugx <= :maxPriceUgx)
                order by ts_rank(l.search_tsv, plainto_tsquery('simple', :query)) desc,
                     l.created_at desc,
                     l.id desc
                """,
            countQuery = """
                select count(*)
                from listings l
                where l.status = 'ACTIVE'
                  and l.search_tsv @@ plainto_tsquery('simple', :query)
                  and (:categoryCode is null or l.category_code = :categoryCode)
                  and (:campus is null or l.campus = :campus)
                  and (:minPriceUgx is null or l.price_ugx >= :minPriceUgx)
                  and (:maxPriceUgx is null or l.price_ugx <= :maxPriceUgx)
                """,
            nativeQuery = true
        )
        Page<ListingCardProjection> searchActive(
            @Param("query") String query,
            @Param("categoryCode") String categoryCode,
            @Param("campus") String campus,
            @Param("minPriceUgx") Long minPriceUgx,
            @Param("maxPriceUgx") Long maxPriceUgx,
            Pageable pageable);

        @Query(
            value = """
                select l.id as id,
                   l.owner_user_id as ownerUserId,
                   l.title as title,
                   l.price_ugx as priceUgx,
                   l.currency as currency,
                   l.category_code as categoryCode,
                   l.location_text as locationText,
                   l.campus as campus,
                   l.status::text as status,
                   l.created_at as createdAt,
                   ST_Distance(
                       l.geo,
                       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
                   ) as distanceMeters
                from listings l
                where l.status = 'ACTIVE'
                  and l.geo is not null
                  and ST_DWithin(
                    l.geo,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    :radiusMeters
                  )
                  and (:categoryCode is null or l.category_code = :categoryCode)
                  and (:campus is null or l.campus = :campus)
                order by distanceMeters asc,
                     l.created_at desc,
                     l.id desc
                """,
            countQuery = """
                select count(*)
                from listings l
                where l.status = 'ACTIVE'
                  and l.geo is not null
                  and ST_DWithin(
                    l.geo,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    :radiusMeters
                  )
                  and (:categoryCode is null or l.category_code = :categoryCode)
                  and (:campus is null or l.campus = :campus)
                """,
            nativeQuery = true
        )
        Page<ListingCardProjection> nearbyActive(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("categoryCode") String categoryCode,
            @Param("campus") String campus,
            Pageable pageable);

    @Transactional
    @Modifying
    @Query(value = """
            update listings
            set geo = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                updated_at = now()
            where id = :listingId
            """, nativeQuery = true)
    int updateGeo(@Param("listingId") Long listingId, @Param("lat") double lat, @Param("lng") double lng);

    @Transactional
    @Modifying
    @Query(value = """
            update listings
            set geo = null,
                updated_at = now()
            where id = :listingId
            """, nativeQuery = true)
    int clearGeo(@Param("listingId") Long listingId);

    // G9 — auto-tag listing with the zone polygon it falls inside
    // flushAutomatically=true: flush pending INSERT before the UPDATE
    // clearAutomatically=true: evict the entity from cache so the subsequent JPA save picks up zone_tag
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update listings
            set zone_tag = (
                select tag from zones
                where ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
                limit 1
            )
            where id = :listingId
            """, nativeQuery = true)
    int autoTagZone(@Param("listingId") Long listingId,
                    @Param("lat") double lat,
                    @Param("lng") double lng);

    // G9 — count active listings in a zone (used by LocationCheckService)
    // Cast status::text so PostgreSQL doesn't complain about listing_status vs character varying
    @Query(value = "select count(*) from listings where zone_tag = :zoneTag and status::text = :status",
           nativeQuery = true)
    long countByZoneTagAndStatus(@Param("zoneTag") String zoneTag, @Param("status") String status);

    // G9 — listings in a specific zone sorted by proximity
    @Query(value = """
            select l.id as id,
                   l.owner_user_id as ownerUserId,
                   l.title as title,
                   l.price_ugx as priceUgx,
                   l.currency as currency,
                   l.category_code as categoryCode,
                   l.location_text as locationText,
                   l.campus as campus,
                   l.status::text as status,
                   l.created_at as createdAt,
                   ST_Distance(l.geo, ST_MakePoint(:lng, :lat)::geography) as distanceMeters
            from listings l
            where l.zone_tag = :zoneTag
              and l.status = 'ACTIVE'
              and l.geo is not null
            order by distanceMeters asc, l.created_at desc, l.id desc
            """,
           countQuery = """
            select count(*) from listings
            where zone_tag = :zoneTag and status = 'ACTIVE'
            """,
           nativeQuery = true)
    Page<ListingCardProjection> findByZoneTagActive(
            @Param("zoneTag") String zoneTag,
            @Param("lat") double lat,
            @Param("lng") double lng,
            Pageable pageable);

    // G1/G9 — global feed sorted by distance (paginated)
    @Query(value = """
            select l.id as id,
                   l.owner_user_id as ownerUserId,
                   l.title as title,
                   l.price_ugx as priceUgx,
                   l.currency as currency,
                   l.category_code as categoryCode,
                   l.location_text as locationText,
                   l.campus as campus,
                   l.status::text as status,
                   l.created_at as createdAt,
                   case when l.geo is not null
                        then ST_Distance(l.geo, ST_MakePoint(:lng, :lat)::geography)
                        else null end as distanceMeters
            from listings l
            where l.status = 'ACTIVE'
            order by distanceMeters asc nulls last, l.created_at desc, l.id desc
            """,
           countQuery = "select count(*) from listings where status = 'ACTIVE'",
           nativeQuery = true)
    Page<ListingCardProjection> findAllActiveByDistance(
            @Param("lat") double lat,
            @Param("lng") double lng,
            Pageable pageable);
}
