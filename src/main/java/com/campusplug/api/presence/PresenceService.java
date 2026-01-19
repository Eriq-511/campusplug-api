package com.campusplug.api.presence;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class PresenceService {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "campusplug:presence:user:";

    private final StringRedisTemplate redis;

    public PresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void markActive(Long userId) {
        if (userId == null) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + userId, "1", TTL);
    }

    public boolean isActive(Long userId) {
        if (userId == null) {
            return false;
        }
        Boolean exists = redis.hasKey(KEY_PREFIX + userId);
        return Boolean.TRUE.equals(exists);
    }
}
