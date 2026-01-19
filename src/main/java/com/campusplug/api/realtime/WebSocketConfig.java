package com.campusplug.api.realtime;

import com.campusplug.api.security.AppCorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompJwtAuthChannelInterceptor stompJwtAuthChannelInterceptor;
    private final AppCorsProperties corsProperties;

    public WebSocketConfig(StompJwtAuthChannelInterceptor stompJwtAuthChannelInterceptor, AppCorsProperties corsProperties) {
        this.stompJwtAuthChannelInterceptor = stompJwtAuthChannelInterceptor;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws");

        if (corsProperties.getAllowedOriginPatterns() != null && !corsProperties.getAllowedOriginPatterns().isEmpty()) {
            endpoint.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns().toArray(String[]::new));
        } else {
            endpoint.setAllowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new));
        }
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompJwtAuthChannelInterceptor);
    }
}
