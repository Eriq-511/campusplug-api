package com.campusplug.api.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetTokenStore {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public PasswordResetTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String createToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(key(token), String.valueOf(userId), TOKEN_TTL);
        return token;
    }

    public Optional<Long> consumeToken(String token) {
        String value = redisTemplate.opsForValue().get(key(token));
        if (value == null) {
            return Optional.empty();
        }
        redisTemplate.delete(key(token));
        return Optional.of(Long.valueOf(value));
    }

    private static String key(String token) {
        return "campusplug:password-reset:token:" + token;
    }
}
