package com.campusplug.api.users;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
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
              case when u.registered_geo is null then null else ST_X(u.registered_geo::geometry) end as registeredLng
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

    boolean existsByRegistrationNumber(String registrationNumber);
}
