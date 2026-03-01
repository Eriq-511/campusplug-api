package com.campusplug.api.realtime;

import com.campusplug.api.listings.ListingEntity;
import com.campusplug.api.users.UserEntity;
import com.campusplug.api.users.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * G4 — Sends FCM push notifications to users near a new listing.
 * All methods are @Async so they never block the caller's response time.
 * FirebaseApp is optional — if no service account is configured, this service is a no-op.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    /** Radius in metres within which users get notified for a new listing */
    private static final double NOTIFY_RADIUS_METERS = 5000.0;

    private final UserRepository userRepository;
    private final FirebaseApp firebaseApp;  // null when FIREBASE_SERVICE_ACCOUNT_JSON is not set

    public PushNotificationService(UserRepository userRepository,
                                   Optional<FirebaseApp> firebaseAppOpt) {
        this.userRepository = userRepository;
        this.firebaseApp = firebaseAppOpt.orElse(null);
        if (this.firebaseApp == null) {
            log.info("FCM push notifications disabled (no Firebase service account configured)");
        }
    }

    /**
     * Notify all users within 5 km of a new listing.
     * Called by ListingService.create() after the listing goes ACTIVE.
     */
    @Async
    public void notifyNearbyUsers(ListingEntity listing) {
        // Intentionally a no-op.
        // Call notifyNearbyUsersWithCoords(...) when coordinates are available.
    }

    /**
     * Preferred variant — called with explicit coords after save.
     */
    @Async
    public void notifyNearbyUsersWithCoords(ListingEntity listing, double lat, double lng) {
        if (!fcmEnabled()) return;

        List<UserEntity> nearby = userRepository.findUsersNearPoint(lat, lng, NOTIFY_RADIUS_METERS);

        for (UserEntity user : nearby) {
            if (user.getFcmToken() == null) continue;
            if (user.getId().equals(listing.getOwnerUserId())) continue;

            Message msg = Message.builder()
                    .setToken(user.getFcmToken())
                    .setNotification(Notification.builder()
                            .setTitle("New listing near you!")
                            .setBody(listing.getTitle() + " — UGX " + formatPrice(listing.getPriceUgx()))
                            .build())
                    .putData("listingId", listing.getId().toString())
                    .putData("type", "NEW_NEARBY_LISTING")
                    .build();

            sendSafe(user, msg);
        }
    }

    private void sendSafe(UserEntity user, Message msg) {
        try {
            FirebaseMessaging.getInstance().send(msg);
        } catch (FirebaseMessagingException e) {
            log.warn("FCM send failed for user={}: {}", user.getId(), e.getMessage());
            if ("registration-token-not-registered".equalsIgnoreCase(e.getErrorCode().name())) {
                // Token stale (device reset / app reinstall) — clear it
                userRepository.clearFcmToken(user.getId());
            }
        }
    }

    private boolean fcmEnabled() {
        return firebaseApp != null;
    }

    private static String formatPrice(Long priceUgx) {
        if (priceUgx == null) return "0";
        return String.format("%,d", priceUgx);
    }
}
