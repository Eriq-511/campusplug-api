package com.campusplug.api.config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.service-account-json:}")
    private String serviceAccountJson;

    /**
     * Only registered when FIREBASE_SERVICE_ACCOUNT_JSON is non-blank.
     * When absent (tests, local dev) no FirebaseApp bean exists and
     * PushNotificationService uses Optional<FirebaseApp> to detect this.
     */
    @Bean
    @ConditionalOnExpression("!'${app.firebase.service-account-json:}'.isBlank()")
    public FirebaseApp firebaseApp() throws Exception {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        String raw = serviceAccountJson.trim();

        // Accept either:
        // 1) raw JSON (starts with '{')
        // 2) base64-encoded JSON (legacy/current default)
        // This makes local setup easier across shells and .env formats.
        byte[] decoded;
        if (raw.startsWith("{")) {
            decoded = raw.getBytes(StandardCharsets.UTF_8);
        } else {
            decoded = Base64.getDecoder().decode(raw);
        }
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(decoded)))
                .build();
        log.info("Firebase Admin SDK initialised — FCM push enabled");
        return FirebaseApp.initializeApp(options);
    }
}
