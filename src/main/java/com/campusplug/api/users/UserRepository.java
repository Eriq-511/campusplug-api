package com.campusplug.api.users;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    interface UserProfileProjection {
        Long getId();

        String getFullName();

        String getEmail();

        String getRegistrationNumber();

        String getPhoneNumber();

        String getCampus();

        String getRegisteredLocationText();

        Double getRegisteredLat();

        Double getRegisteredLng();

        String getAlternateLocationText();

        Double getAlternateLat();

        Double getAlternateLng();

        String getAvatarUrl();
    }

    @Query("select u from UserEntity u where lower(u.email) = lower(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

    @Query(value = """
            select
              u.id as id,
              u.full_name as fullName,
              u.email as email,
              u.registration_number as registrationNumber,
              u.phone_number as phoneNumber,
              u.campus as campus,
              u.registered_location_text as registeredLocationText,
              case when u.registered_geo is null then null else ST_Y(u.registered_geo::geometry) end as registeredLat,
                            case when u.registered_geo is null then null else ST_X(u.registered_geo::geometry) end as registeredLng,
                            u.alternate_location_text as alternateLocationText,
                            case when u.alternate_geo is null then null else ST_Y(u.alternate_geo::geometry) end as alternateLat,
                            case when u.alternate_geo is null then null else ST_X(u.alternate_geo::geometry) end as alternateLng,
                            u.avatar_url as avatarUrl
            from users u
            where lower(u.email) = lower(:email)
            """, nativeQuery = true)
    Optional<UserProfileProjection> findProfileByEmailIgnoreCase(@Param("email") String email);

    @Transactional
    @Modifying
    @Query(value = """
            update users
            set registered_geo = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                updated_at = now()
            where id = :userId
            """, nativeQuery = true)
    int updateRegisteredGeo(@Param("userId") Long userId, @Param("lat") double lat, @Param("lng") double lng);

    @Transactional
    @Modifying
    @Query(value = """
            update users
            set registered_geo = null,
                updated_at = now()
            where id = :userId
            """, nativeQuery = true)
    int clearRegisteredGeo(@Param("userId") Long userId);

    @Transactional
    @Modifying
    @Query(value = """
            update users
            set alternate_geo = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                updated_at = now()
            where id = :userId
            """, nativeQuery = true)
    int updateAlternateGeo(@Param("userId") Long userId, @Param("lat") double lat, @Param("lng") double lng);

    @Transactional
    @Modifying
    @Query(value = """
            update users
            set alternate_geo = null,
                updated_at = now()
            where id = :userId
            """, nativeQuery = true)
    int clearAlternateGeo(@Param("userId") Long userId);

    // G3 — update live location
    @Transactional
    @Modifying
    @Query(value = """
            update users
            set last_known_lat  = :lat,
                last_known_lng  = :lng,
                last_geo        = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                last_location_at = now(),
                updated_at      = now()
            where id = :userId
            """, nativeQuery = true)
    int updateLastLocation(@Param("userId") Long userId, @Param("lat") double lat, @Param("lng") double lng);

    // G4 — FCM token management
    @Transactional
    @Modifying
    @Query(value = "update users set fcm_token = :token, updated_at = now() where id = :userId",
           nativeQuery = true)
    int updateFcmToken(@Param("userId") Long userId, @Param("token") String token);

    @Transactional
    @Modifying
    @Query(value = "update users set fcm_token = null, updated_at = now() where id = :userId",
           nativeQuery = true)
    int clearFcmToken(@Param("userId") Long userId);

    // Avatar (profile picture)
    @Transactional
    @Modifying
    @Query(value = "update users set avatar_url = :avatarUrl, avatar_public_id = :avatarPublicId, updated_at = now() where id = :userId",
           nativeQuery = true)
    int updateAvatar(@Param("userId") Long userId, @Param("avatarUrl") String avatarUrl, @Param("avatarPublicId") String avatarPublicId);

    // G4 — Find users near a point who have an FCM token (for push notifications)
    @Query(value = """
            select * from users
            where last_geo is not null
              and fcm_token is not null
              and ST_DWithin(last_geo, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
            """, nativeQuery = true)
    java.util.List<UserEntity> findUsersNearPoint(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") double radiusMeters);

    boolean existsByRegistrationNumber(String registrationNumber);

    // Public-facing projection: never exposes password hash, FCM token, or
    // geo coordinates. Safe to return to any authenticated student.
    interface PublicUserProjection {
        Long getId();
        String getFullName();
        String getCampus();
        String getAvatarUrl();
        Instant getCreatedAt();
    }

    @Query(value = """
            select u.id          as id,
                   u.full_name   as fullName,
                   u.campus      as campus,
                   u.avatar_url  as avatarUrl,
                   u.created_at  as createdAt
            from users u
            where u.id = :id
            """, nativeQuery = true)
    Optional<PublicUserProjection> findPublicById(@Param("id") Long id);
}
