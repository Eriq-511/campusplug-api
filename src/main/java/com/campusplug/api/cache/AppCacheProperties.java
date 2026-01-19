package com.campusplug.api.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cache")
public class AppCacheProperties {

    /** Prefix applied to all Redis cache keys. */
    private String keyPrefix = "campusplug:cache:";

    /** Default TTLs in seconds (can be overridden in tests/prod). */
    private long categoriesTtlSeconds = 15 * 60;
    private long searchTtlSeconds = 30;
    private long nearbyTtlSeconds = 30;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public long getCategoriesTtlSeconds() {
        return categoriesTtlSeconds;
    }

    public void setCategoriesTtlSeconds(long categoriesTtlSeconds) {
        this.categoriesTtlSeconds = categoriesTtlSeconds;
    }

    public long getSearchTtlSeconds() {
        return searchTtlSeconds;
    }

    public void setSearchTtlSeconds(long searchTtlSeconds) {
        this.searchTtlSeconds = searchTtlSeconds;
    }

    public long getNearbyTtlSeconds() {
        return nearbyTtlSeconds;
    }

    public void setNearbyTtlSeconds(long nearbyTtlSeconds) {
        this.nearbyTtlSeconds = nearbyTtlSeconds;
    }
}
