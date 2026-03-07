package com.campusplug.api.auth.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PendingRegistrationStore {

    private static final Duration PENDING_TTL = Duration.ofMinutes(15);
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PendingRegistrationStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(PendingRegistration pending) {
        try {
            String json = objectMapper.writeValueAsString(pending);
            redisTemplate.opsForValue().set(pendingKey(pending.getEmail()), json, PENDING_TTL);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize pending registration", ex);
        }
    }

    public Optional<PendingRegistration> find(String email) {
        String json = redisTemplate.opsForValue().get(pendingKey(email));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PendingRegistration.class));
        } catch (JsonProcessingException ex) {
            redisTemplate.delete(pendingKey(email));
            return Optional.empty();
        }
    }

    public void markOtpVerified(String email) {
        redisTemplate.opsForValue().set(verifiedKey(email), "1", VERIFIED_TTL);
    }

    public boolean isOtpVerified(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(verifiedKey(email)));
    }

    public void clear(String email) {
        redisTemplate.delete(pendingKey(email));
        redisTemplate.delete(verifiedKey(email));
    }

    private static String pendingKey(String email) {
        return "campusplug:auth:reg:pending:" + normalize(email);
    }

    private static String verifiedKey(String email) {
        return "campusplug:auth:reg:verified:" + normalize(email);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static class PendingRegistration {
        private String fullName;
        private String registrationNumber;
        private String email;
        private String phoneNumber;
        private String campus;
        private String registeredLocationLabel;
        private Double registeredLocationLat;
        private Double registeredLocationLng;
        private String alternateLocationLabel;
        private Double alternateLocationLat;
        private Double alternateLocationLng;

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getRegistrationNumber() {
            return registrationNumber;
        }

        public void setRegistrationNumber(String registrationNumber) {
            this.registrationNumber = registrationNumber;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        public String getCampus() {
            return campus;
        }

        public void setCampus(String campus) {
            this.campus = campus;
        }

        public String getRegisteredLocationLabel() {
            return registeredLocationLabel;
        }

        public void setRegisteredLocationLabel(String registeredLocationLabel) {
            this.registeredLocationLabel = registeredLocationLabel;
        }

        public Double getRegisteredLocationLat() {
            return registeredLocationLat;
        }

        public void setRegisteredLocationLat(Double registeredLocationLat) {
            this.registeredLocationLat = registeredLocationLat;
        }

        public Double getRegisteredLocationLng() {
            return registeredLocationLng;
        }

        public void setRegisteredLocationLng(Double registeredLocationLng) {
            this.registeredLocationLng = registeredLocationLng;
        }

        public String getAlternateLocationLabel() {
            return alternateLocationLabel;
        }

        public void setAlternateLocationLabel(String alternateLocationLabel) {
            this.alternateLocationLabel = alternateLocationLabel;
        }

        public Double getAlternateLocationLat() {
            return alternateLocationLat;
        }

        public void setAlternateLocationLat(Double alternateLocationLat) {
            this.alternateLocationLat = alternateLocationLat;
        }

        public Double getAlternateLocationLng() {
            return alternateLocationLng;
        }

        public void setAlternateLocationLng(Double alternateLocationLng) {
            this.alternateLocationLng = alternateLocationLng;
        }
    }
}
