package com.campusplug.api.auth.service;

import java.security.SecureRandom;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Stores a 5-digit numeric OTP in Redis keyed by (lowercase) email for
 * the password-reset flow. TTL: 10 minutes. Consuming the OTP deletes it
 * (one-time use).
 */
@Service
public class PasswordResetOtpStore {

    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    public PasswordResetOtpStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Generates, stores and returns a 5-digit OTP for the given email (matches the 5-box UI). */
    public String createOtp(String email) {
        String otp = String.format("%05d", RANDOM.nextInt(100_000));
        redisTemplate.opsForValue().set(key(email), otp, OTP_TTL);
        return otp;
    }

    /**
     * Validates and consumes the OTP.
     * Returns {@code true} and deletes the entry if valid; {@code false} otherwise.
     */
    public boolean consumeOtp(String email, String otp) {
        String stored = redisTemplate.opsForValue().get(key(email));
        if (stored == null || !stored.equals(otp)) {
            return false;
        }
        redisTemplate.delete(key(email));
        return true;
    }

    public boolean hasActiveOtp(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(email)));
    }

    private static String key(String email) {
        return "campusplug:auth:pwreset-otp:" + email.toLowerCase();
    }
}
