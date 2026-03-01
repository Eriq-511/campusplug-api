package com.campusplug.api.zones;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserZoneRepository extends JpaRepository<UserZoneEntity, Long> {
    Optional<UserZoneEntity> findByUserId(Long userId);
}
