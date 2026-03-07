package com.campusplug.api.auth;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campusplug.api.auth.dto.AuthResponse;
import com.campusplug.api.auth.dto.ForgotPasswordRequest;
import com.campusplug.api.auth.dto.ForgotPasswordResponse;
import com.campusplug.api.auth.dto.LoginRequest;
import com.campusplug.api.auth.dto.OtpVerifyRequest;
import com.campusplug.api.auth.dto.RegisterRequest;
import com.campusplug.api.auth.dto.RegisterSetPasswordRequest;
import com.campusplug.api.auth.dto.RegisterStartRequest;
import com.campusplug.api.auth.dto.ResetPasswordRequest;
import com.campusplug.api.auth.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/register/start")
    public ResponseEntity<?> registerStart(@Valid @RequestBody RegisterStartRequest request) {
        return ResponseEntity.ok(authService.registerStart(request));
    }

    @PostMapping("/register/verify-otp")
    public ResponseEntity<?> verifyRegisterOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return ResponseEntity.ok(authService.verifyRegisterOtp(request));
    }

    @PostMapping("/register/set-password")
    public AuthResponse registerSetPassword(@Valid @RequestBody RegisterSetPasswordRequest request) {
        return authService.registerSetPassword(request);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        authService.logout(authHeader);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request.getEmail());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password reset successful"));
    }
}
