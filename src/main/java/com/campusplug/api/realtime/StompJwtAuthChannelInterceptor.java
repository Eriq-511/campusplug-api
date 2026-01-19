package com.campusplug.api.realtime;

import com.campusplug.api.common.ApiException;
import com.campusplug.api.security.jwt.JwtService;
import com.campusplug.api.security.jwt.RevokedTokenStore;
import com.campusplug.api.users.UserEntity;
import com.campusplug.api.users.UserRepository;
import com.campusplug.api.presence.PresenceService;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

@Component
public class StompJwtAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final RevokedTokenStore revokedTokenStore;
    private final UserRepository userRepository;
    private final PresenceService presenceService;

    public StompJwtAuthChannelInterceptor(
            JwtService jwtService,
            RevokedTokenStore revokedTokenStore,
            UserRepository userRepository,
            PresenceService presenceService) {
        this.jwtService = jwtService;
        this.revokedTokenStore = revokedTokenStore;
        this.userRepository = userRepository;
        this.presenceService = presenceService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String auth = firstNativeHeader(accessor, "Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                throw new MessagingException("Missing Authorization header");
            }

            String token = auth.substring("Bearer ".length()).trim();
            DecodedJWT jwt;
            try {
                jwt = jwtService.verify(token);
            } catch (Exception ex) {
                throw new MessagingException("Invalid token", ex);
            }

            String jti = jwt.getId();
            if (jti != null && revokedTokenStore.isRevoked(jti)) {
                throw new MessagingException("Token revoked");
            }

            Long userId;
            try {
                userId = Long.parseLong(jwt.getSubject());
            } catch (Exception ex) {
                throw new MessagingException("Invalid token subject", ex);
            }

            UserEntity user = userRepository.findById(userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));

            Principal principal = new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of());
            accessor.setUser(principal);

            presenceService.markActive(user.getId());
        }

        return message;
    }

    private static String firstNativeHeader(StompHeaderAccessor accessor, String headerName) {
        List<String> values = accessor.getNativeHeader(headerName);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
