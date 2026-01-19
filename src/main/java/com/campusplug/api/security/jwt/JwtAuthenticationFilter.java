package com.campusplug.api.security.jwt;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.campusplug.api.users.UserEntity;
import com.campusplug.api.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final RevokedTokenStore revokedTokenStore;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, RevokedTokenStore revokedTokenStore, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.revokedTokenStore = revokedTokenStore;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring("Bearer ".length()).trim();
        try {
            DecodedJWT jwt = jwtService.verify(token);
            String jti = jwt.getId();

            if (jti != null && revokedTokenStore.isRevoked(jti)) {
                filterChain.doFilter(request, response);
                return;
            }

            String subject = jwt.getSubject();
            if (subject == null) {
                filterChain.doFilter(request, response);
                return;
            }

            Long userId = Long.valueOf(subject);
            Optional<UserEntity> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }

            UserEntity userEntity = userOpt.get();
            UserDetails principal = User.withUsername(userEntity.getEmail())
                    .password(userEntity.getPasswordHash())
                    .authorities(Collections.emptyList())
                    .build();

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } catch (JWTVerificationException | IllegalArgumentException ignored) {
            // Invalid token -> treat as anonymous.
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
        }
    }
}
