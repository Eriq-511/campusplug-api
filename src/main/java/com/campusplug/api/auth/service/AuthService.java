package com.campusplug.api.auth.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.campusplug.api.auth.dto.AuthResponse;
import com.campusplug.api.auth.dto.ForgotPasswordResponse;
import com.campusplug.api.auth.dto.LoginRequest;
import com.campusplug.api.auth.dto.RegisterRequest;
import com.campusplug.api.auth.dto.ResetPasswordRequest;
import com.campusplug.api.auth.util.RegistrationNumberNormalizer;
import com.campusplug.api.common.ApiException;
import com.campusplug.api.security.jwt.JwtService;
import com.campusplug.api.security.jwt.RevokedTokenStore;
import com.campusplug.api.users.UserEntity;
import com.campusplug.api.users.UserRepository;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RevokedTokenStore revokedTokenStore;
    private final EmailDomainValidator emailDomainValidator;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final Environment environment;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RevokedTokenStore revokedTokenStore,
            EmailDomainValidator emailDomainValidator,
            PasswordResetTokenStore passwordResetTokenStore,
            Environment environment) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.revokedTokenStore = revokedTokenStore;
        this.emailDomainValidator = emailDomainValidator;
        this.passwordResetTokenStore = passwordResetTokenStore;
        this.environment = environment;
    }

    public AuthResponse register(RegisterRequest req) {
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

        UserEntity saved = userRepository.save(user);
        String token = jwtService.createToken(saved);
        return new AuthResponse(token, toSummary(saved));
    }

    public AuthResponse login(LoginRequest req) {
        String email = normalizeEmail(req.getEmail());
        emailDomainValidator.validateAllowedDomain(email);

        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        String token = jwtService.createToken(user);
        return new AuthResponse(token, toSummary(user));
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

        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(email);
        String token = null;
        if (userOpt.isPresent()) {
            token = passwordResetTokenStore.createToken(userOpt.get().getId());
        }

        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));
        if (isProd) {
            token = null;
        }

        return new ForgotPasswordResponse(
                "If the email exists, a password reset link/token will be sent.",
                token
        );
    }

    public void resetPassword(ResetPasswordRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "confirmPassword must match password");
        }

        Long userId = passwordResetTokenStore.consumeToken(req.getToken())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "Reset token is invalid or expired"));

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "Reset token is invalid or expired"));

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
}
