package com.campusplug.api.auth.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.campusplug.api.auth.config.AuthProperties;
import com.campusplug.api.auth.dto.AuthResponse;
import com.campusplug.api.auth.dto.ForgotPasswordResponse;
import com.campusplug.api.auth.dto.LoginRequest;
import com.campusplug.api.auth.dto.OtpVerifyRequest;
import com.campusplug.api.auth.dto.RegisterRequest;
import com.campusplug.api.auth.dto.ResetPasswordRequest;
import com.campusplug.api.auth.util.RegistrationNumberNormalizer;
import com.campusplug.api.common.ApiException;
import com.campusplug.api.security.jwt.JwtService;
import com.campusplug.api.security.jwt.RevokedTokenStore;
import com.campusplug.api.users.UserEntity;
import com.campusplug.api.users.UserRepository;
import com.campusplug.api.users.dto.RegisteredLocationDto;

@Service
public class AuthService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RevokedTokenStore revokedTokenStore;
    private final EmailDomainValidator emailDomainValidator;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final PasswordResetOtpStore passwordResetOtpStore;
    private final Environment environment;
    private final EmailService emailService;
    private final AuthProperties authProperties;
    private final OtpStore otpStore;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RevokedTokenStore revokedTokenStore,
            EmailDomainValidator emailDomainValidator,
            PasswordResetTokenStore passwordResetTokenStore,
            PasswordResetOtpStore passwordResetOtpStore,
            Environment environment,
            EmailService emailService,
            AuthProperties authProperties,
            OtpStore otpStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.revokedTokenStore = revokedTokenStore;
        this.emailDomainValidator = emailDomainValidator;
        this.passwordResetTokenStore = passwordResetTokenStore;
        this.passwordResetOtpStore = passwordResetOtpStore;
        this.environment = environment;
        this.emailService = emailService;
        this.authProperties = authProperties;
        this.otpStore = otpStore;
    }

    @Transactional
    public Object register(RegisterRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "confirmPassword must match password");
        }

        String email = normalizeEmail(req.getEmail());
        emailDomainValidator.validateAllowedDomain(email);

        String regNo;
        try {
            regNo = RegistrationNumberNormalizer.normalize(req.getRegistrationNumber());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REGISTRATION_NUMBER", ex.getMessage());
        }

        String phone = normalizePhone(req.getPhoneNumber());
        if (phone != null && !E164.matcher(phone).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER", "phoneNumber must be E.164 (e.g. +256700000000)");
        }

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "Email already registered");
        }
        if (userRepository.existsByRegistrationNumber(regNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION_NUMBER", "Registration number already registered");
        }

        UserEntity user = new UserEntity();
        user.setFullName(req.getFullName().trim());
        user.setEmail(email);
        user.setRegistrationNumber(regNo);
        user.setPhoneNumber(phone);
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));

        user.setCampus(normalizeCampus(req.getCampus()));
        applySavedLocationOnRegister(user, req.getRegisteredLocation(), req.getAlternateLocation());

        UserEntity saved = userRepository.save(user);

        persistSavedLocationGeoOnRegister(saved.getId(), req.getRegisteredLocation(), req.getAlternateLocation());

        if (!authProperties.isOtpEnabled()) {
            // OTP disabled — return JWT directly (preserves existing test behaviour)
            String token = jwtService.createToken(saved);
            return new AuthResponse(token, toSummary(saved));
        }

        // OTP enabled — send email verification code; JWT only issued after /verify-otp
        String otp = otpStore.createOtp(email);
        emailService.sendOtpEmail(email, otp);

        String masked = maskEmail(email);
        return Map.of(
                "status", "OTP_SENT",
                "message", "A 6-digit verification code was sent to " + masked
        );
    }

    /**
     * When OTP is disabled (default): returns AuthResponse with JWT immediately.
     * When OTP is enabled: validates credentials, sends a 6-digit code by email,
     * and returns a pending response. The JWT is only issued after /verify-otp.
     */
    public Object login(LoginRequest req) {
        String email = normalizeEmail(req.getEmail());
        emailDomainValidator.validateAllowedDomain(email);

        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        if (!authProperties.isOtpEnabled()) {
            // OTP disabled — return JWT directly (preserves existing test behaviour)
            return new AuthResponse(jwtService.createToken(user), toSummary(user));
        }

        // OTP enabled — generate code, send email, return pending status
        String otp = otpStore.createOtp(email);
        emailService.sendOtpEmail(email, otp);

        String masked = maskEmail(email);
        return Map.of(
                "status", "OTP_SENT",
                "message", "A 6-digit verification code was sent to " + masked
        );
    }

    /**
     * Second step of OTP login: validates the 6-digit code and returns the JWT.
     */
    public AuthResponse verifyOtp(OtpVerifyRequest req) {
        String email = normalizeEmail(req.getEmail());
        emailDomainValidator.validateAllowedDomain(email);

        boolean valid = otpStore.consumeOtp(email, req.getOtp());
        if (!valid) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_OTP",
                    "The code is incorrect or has expired. Request a new one by logging in again.");
        }

        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "User not found"));

        return new AuthResponse(jwtService.createToken(user), toSummary(user));
    }

    /** Masks an email: e.g. eriq@gmail.com → er**@gmail.com */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.substring(0, 2) + "**" + email.substring(at);
    }

    public void logout(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing Bearer token");
        }

        String token = authorizationHeader.substring("Bearer ".length()).trim();
        DecodedJWT jwt = jwtService.verify(token);

        String jti = jwt.getId();
        if (jti == null || jti.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Token has no jti");
        }

        Instant expiresAt = jwt.getExpiresAt().toInstant();
        long ttlSeconds = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        revokedTokenStore.revoke(jti, ttlSeconds);
    }

    public ForgotPasswordResponse forgotPassword(String emailInput) {
        String email = normalizeEmail(emailInput);
        emailDomainValidator.validateAllowedDomain(email);

        String devOtp = null;
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            // Generate a 6-digit numeric OTP (matches the UI code-entry screen)
            String otp = passwordResetOtpStore.createOtp(email);
            // Send email asynchronously — never blocks the HTTP response
            emailService.sendPasswordResetOtpEmail(email, otp);

            boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));
            devOtp = isProd ? null : otp;
        }

        return new ForgotPasswordResponse(
                "If the email exists, a 6-digit reset code will be sent to it.",
                devOtp
        );
    }

    public void resetPassword(ResetPasswordRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "confirmPassword must match password");
        }

        String email = normalizeEmail(req.getEmail());

        // Validate the 6-digit OTP the user entered on the Forgot-Password screen
        boolean valid = passwordResetOtpStore.consumeOtp(email, req.getOtp());
        if (!valid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE",
                    "The reset code is incorrect or has expired. Request a new one.");
        }

        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE", "Reset code is invalid or expired"));

        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        userRepository.save(user);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.replace(" ", "");
    }

    private static AuthResponse.UserSummary toSummary(UserEntity u) {
        return new AuthResponse.UserSummary(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getRegistrationNumber(),
                u.getPhoneNumber()
        );
    }

    private static void applySavedLocationOnRegister(UserEntity user, RegisteredLocationDto registered, RegisteredLocationDto alternate) {
        if (!hasAnyLocationPayload(registered) && !hasAnyLocationPayload(alternate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOCATION_REQUIRED", "Either registeredLocation or alternateLocation is required");
        }

        if (hasAnyLocationPayload(registered) && hasAnyLocationPayload(alternate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOCATION_CONFLICT", "Provide only one of registeredLocation or alternateLocation");
        }

        if (hasAnyLocationPayload(registered)) {
            String label = requireLocationLabelLatLng(registered, "registeredLocation");
            user.setRegisteredLocationText(label);
            user.setAlternateLocationText(null);
            return;
        }

        String label = requireLocationLabelLatLng(alternate, "alternateLocation");
        user.setAlternateLocationText(label);
        user.setRegisteredLocationText(null);
    }

    private void persistSavedLocationGeoOnRegister(Long userId, RegisteredLocationDto registered, RegisteredLocationDto alternate) {
        // Geo fields are maintained via native queries.
        if (hasAnyLocationPayload(registered)) {
            userRepository.updateRegisteredGeo(userId, registered.getLat(), registered.getLng());
            userRepository.clearAlternateGeo(userId);
            return;
        }
        if (hasAnyLocationPayload(alternate)) {
            userRepository.updateAlternateGeo(userId, alternate.getLat(), alternate.getLng());
            userRepository.clearRegisteredGeo(userId);
        }
    }

    private static boolean hasAnyLocationPayload(RegisteredLocationDto loc) {
        if (loc == null) {
            return false;
        }
        String label = loc.getLabel();
        return (label != null && !label.isBlank()) || loc.getLat() != null || loc.getLng() != null;
    }

    private static String requireLocationLabelLatLng(RegisteredLocationDto loc, String fieldName) {
        String label = loc.getLabel() == null ? null : loc.getLabel().trim();
        if (label == null || label.isBlank() || loc.getLat() == null || loc.getLng() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", fieldName + " requires label, lat and lng");
        }
        return label;
    }

    private static String normalizeCampus(String campus) {
        if (campus == null) {
            return null;
        }
        String trimmed = campus.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
