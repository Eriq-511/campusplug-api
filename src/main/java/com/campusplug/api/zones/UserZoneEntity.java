package com.campusplug.api.zones;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_zones")
public class UserZoneEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "zone_tag", nullable = false)
    private String zoneTag;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserZoneEntity() {}

    public UserZoneEntity(Long userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getZoneTag() { return zoneTag; }
    public void setZoneTag(String zoneTag) { this.zoneTag = zoneTag; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
