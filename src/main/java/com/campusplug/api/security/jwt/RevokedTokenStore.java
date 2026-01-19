package com.campusplug.api.security.jwt;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RevokedTokenStore {

    private final StringRedisTemplate redisTemplate;

    public RevokedTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revoke(String jti, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            ttlSeconds = 1;
        }
        redisTemplate.opsForValue().set(key(jti), "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isRevoked(String jti) {
        Boolean hasKey = redisTemplate.hasKey(key(jti));
        return Boolean.TRUE.equals(hasKey);
    }

    private static String key(String jti) {
        return "campusplug:revoked:jti:" + jti;
    }
}
