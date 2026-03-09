package com.campusplug.api.auth.service;

import java.security.SecureRandom;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Stores a 5-digit OTP in Redis keyed by (lowercase) email.
 * TTL: 5 minutes. Consuming the OTP deletes it (one-time use).
 */
@Service
public class OtpStore {

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    public OtpStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Generates, stores and returns a 5-digit OTP for the given email. */
    public String createOtp(String email) {
        String otp = String.format("%05d", RANDOM.nextInt(100_000));
        redisTemplate.opsForValue().set(key(email), otp, OTP_TTL);
        return otp;
    }

    /**
     * Validates the OTP for the given email.
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

    /** For rate-limiting / UX: check if an OTP already exists (still within TTL). */
    public boolean hasActiveOtp(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(email)));
    }

    /** Removes a stored OTP without consuming it (e.g. on email delivery failure). */
    public void deleteOtp(String email) {
        redisTemplate.delete(key(email));
    }

    private static String key(String email) {
        return "campusplug:auth:otp:" + email.toLowerCase();
    }
}
