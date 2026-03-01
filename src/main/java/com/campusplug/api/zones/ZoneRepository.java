package com.campusplug.api.zones;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ZoneRepository extends JpaRepository<ZoneEntity, Long> {

    /** ST_Contains — exact zone match: point is strictly inside the polygon */
    @Query(value = """
            select id, name, tag, access_type
            from zones
            where ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
            limit 1
            """, nativeQuery = true)
    Optional<ZoneProjection> findZoneContaining(
            @Param("lat") double lat,
            @Param("lng") double lng);

    /** ST_DWithin — buffer zone: within 300 m of a zone boundary but NOT inside */
    @Query(value = """
            select z.id, z.name, z.tag, 'buffer' as access_type
            from zones z
            where ST_DWithin(
                  z.boundary::geography,
                  ST_MakePoint(:lng, :lat)::geography,
                  300
                )
              and not ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
            order by ST_Distance(z.boundary::geography, ST_MakePoint(:lng, :lat)::geography) asc
            limit 1
            """, nativeQuery = true)
    Optional<ZoneProjection> findBufferZone(
            @Param("lat") double lat,
            @Param("lng") double lng);

    /** All zones (id/name/tag/access_type only — no geometry) for map screen */
    @Query(value = "select id, name, tag, access_type from zones order by name asc",
           nativeQuery = true)
    List<ZoneProjection> findAllProjected();

    interface ZoneProjection {
        Long getId();
        String getName();
        String getTag();
        String getAccessType();
    }
}
