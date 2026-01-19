package com.campusplug.api.security.ratelimit;

import com.campusplug.api.common.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

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

        if (path.equals("/api/v1/auth/login") && method.equals("POST")) {
            allowed =
                    rateLimiter.allow("campusplug:rl:login:ip:" + ip, 5, Duration.ofMinutes(1))
                            && rateLimiter.allow("campusplug:rl:login:email:" + emailKey(effectiveRequest), 5, Duration.ofMinutes(1));
        } else if (path.equals("/api/v1/auth/forgot-password") && method.equals("POST")) {
            allowed =
                    rateLimiter.allow("campusplug:rl:forgot:ip:" + ip, 3, Duration.ofHours(1))
                            && rateLimiter.allow("campusplug:rl:forgot:email:" + emailKey(effectiveRequest), 3, Duration.ofHours(1));
        } else if (path.equals("/api/v1/auth/register") && method.equals("POST")) {
            allowed = rateLimiter.allow("campusplug:rl:register:ip:" + ip, 3, Duration.ofHours(1));
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
