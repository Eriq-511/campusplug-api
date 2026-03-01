package com.campusplug.api.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.util.Base64;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.service-account-json:}")
    private String serviceAccountJson;

    @Bean
    public FirebaseApp firebaseApp() throws Exception {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("app.firebase.service-account-json not set — FCM push notifications disabled");
            return null;
        }
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        byte[] decoded = Base64.getDecoder().decode(serviceAccountJson.trim());
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(decoded)))
                .build();
        log.info("Firebase Admin SDK initialised — FCM push enabled");
        return FirebaseApp.initializeApp(options);
    }
}
