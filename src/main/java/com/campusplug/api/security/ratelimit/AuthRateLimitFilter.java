package com.campusplug.api.security.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.campusplug.api.common.ApiErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        HttpServletRequest effectiveRequest = request;
        if (request.getRequestURI().startsWith("/api/v1/auth/")
                && request.getContentType() != null
                && request.getContentType().toLowerCase(Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE)) {
            effectiveRequest = new CachedBodyHttpServletRequest(request);
        }

        String path = effectiveRequest.getRequestURI();
        if (!path.startsWith("/api/v1/auth/")) {
            filterChain.doFilter(effectiveRequest, response);
            return;
        }

        String ip = ClientIpResolver.resolve(effectiveRequest);
        String method = effectiveRequest.getMethod().toUpperCase(Locale.ROOT);

        boolean allowed = true;

        if (path.equals("/api/v1/auth/forgot-password") && method.equals("POST")) {
            allowed =
                    rateLimiter.allow("campusplug:rl:forgot:ip:" + ip, 5, Duration.ofMinutes(2))
                            && rateLimiter.allow("campusplug:rl:forgot:email:" + emailKey(effectiveRequest), 5, Duration.ofMinutes(2));
        }

        if (path.equals("/api/v1/auth/verify-otp") && method.equals("POST")) {
            // 5 attempts per 2 min per email — brute-force protection for 6-digit OTP
            allowed = rateLimiter.allow("campusplug:rl:otp:email:" + emailKey(effectiveRequest), 5, Duration.ofMinutes(2));
        }

        if (path.equals("/api/v1/auth/reset-password") && method.equals("POST")) {
            // 5 attempts per 2 min per email — brute-force protection for 6-digit reset OTP
            allowed = rateLimiter.allow("campusplug:rl:resetpw:email:" + emailKey(effectiveRequest), 5, Duration.ofMinutes(2));
        }

        if (!allowed) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiErrorResponse body = new ApiErrorResponse(
                    Instant.now(),
                    429,
                    "Too Many Requests",
                    "RATE_LIMITED",
                    "Too many requests",
                    effectiveRequest.getRequestURI(),
                    null
            );
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(effectiveRequest, response);
    }

    private String emailKey(@NonNull HttpServletRequest request) {
        String email = "";

        if (request instanceof CachedBodyHttpServletRequest cached) {
            try {
                JsonNode node = objectMapper.readTree(cached.getCachedBody());
                if (node != null && node.hasNonNull("email")) {
                    email = node.get("email").asText("");
                }
            } catch (IOException | RuntimeException ignored) {
                email = "";
            }
        } else {
            String param = request.getParameter("email");
            email = param == null ? "" : param;
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }
}
