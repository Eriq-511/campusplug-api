# CampusPlug — GIS & Geofencing Integration Plan

> **Stack:** Google Maps (rendering) + Native Device Geofencing (background events) + Spring Boot + PostGIS (data + nearby queries)
>
> **Geofencing approach:** No external accounts or API keys needed.
> - **Primary:** `geofence_service` Flutter package — wraps Android `GeofencingClient` + iOS `CLLocationManager` directly (same underlying API that Radar.io and BlueDot.io use, but free and self-contained)
> - **Fallback:** `workmanager` Flutter package — scheduled background task every 15 min for when the OS kills the geofence service
>
> **Constraint:** $0 budget. Zero third-party geofencing accounts. All services used are free at university-app scale.
>
> **Scope:** Extend the existing CampusPlug backend API to support location-aware features: nearby listings on a map, distance display, user location tracking, and background geofence notifications when users enter/exit campus.

---

## Why No Radar.io / BlueDot.io

Both Radar.io and BlueDot.io are wrappers around the **same native OS APIs** that Android and iOS already provide for free:

| What they use underneath | What we use directly |
|---|---|
| Android `GeofencingClient` (Google Play Services) | `geofence_service` Flutter package |
| iOS `CLLocationManager` region monitoring | `geofence_service` Flutter package |
| Scheduled background checks | `workmanager` Flutter package |

Using them directly means:
- No account signup
- No API keys
- No monthly limits
- No webhook dependency on a sleeping Render instance
- Same accuracy and battery efficiency

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                          Flutter App                              │
│                                                                   │
│  google_maps_flutter → renders map + listing pins + radius circle │
│  geolocator          → gets current device lat/lng               │
│  geofence_service    → PRIMARY: native OS geofence enter/exit     │
│  workmanager         → FALLBACK: 15-min background location check │
│  firebase_messaging  → receives FCM push notifications            │
└──────────────────────────┬────────────────────────────────────────┘
                           │ Direct REST calls (no webhook middleman)
                           ▼
┌─────────────────────────────────────────────────────┐
│              CampusPlug API (Spring Boot)             │
│                                                      │
│  PUT  /api/v1/users/location    ← geofence event     │
│  PUT  /api/v1/users/fcm-token   ← register device    │
│  GET  /api/v1/listings/nearby   ← PostGIS ST_DWithin │
│  GET  /api/v1/geo/geocode       ← geocoding proxy    │
│  GET  /api/v1/geo/reverse       ← reverse geocode    │
│                                                      │
│  PostGIS ST_DWithin + GIST index                     │
│  Firebase Admin SDK → FCM push sender                │
└──────────────────────────┬──────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   Firebase FCM (free)   │
              │  push to nearby users   │
              └────────────────────────┘
```

---

## How geofence_service + workmanager Work Together

```
App is FOREGROUND or BACKGROUND (not killed):
  geofence_service is active
  → OS fires enter/exit via Android GeofencingClient / iOS CLLocationManager
  → Flutter calls PUT /api/v1/users/location immediately

App is KILLED by OS (battery saver, swipe away):
  geofence_service stops
  → workmanager keeps running (registered with Android JobScheduler)
  → fires every 15 min
  → gets location, checks distance to campus
  → if state changed → calls PUT /api/v1/users/location
  → restarts geofence_service

Both paths result in the same backend call.
Backend does not care which one fired it.
```

---

## Flutter Package Summary

```yaml
# pubspec.yaml additions
dependencies:
  google_maps_flutter: ^2.5.3      # map rendering + markers
  geolocator: ^11.0.0              # get device lat/lng
  geofence_service: ^5.0.0         # PRIMARY native geofencing
  workmanager: ^0.5.0              # FALLBACK background task
  firebase_messaging: ^14.0.0      # receive FCM push
  shared_preferences: ^2.2.0       # persist geofence state between sessions
  http: ^1.2.0                     # HTTP calls in background isolate
```

---

## Phases

---

### Phase G1 — Enrich Nearby Listings Response with Distance

**Goal:** Add `distanceMeters` to listing responses returned by `/listings/nearby` so Flutter can display "1.2 km away" under each listing card without a second API call.

**Why first:** Zero external dependencies — purely internal Spring Boot + PostGIS change. Unblocks all Google Maps rendering in Flutter immediately.

**Deliverables**
- [ ] Add `distanceMeters` (nullable `Double`) to `ListingResponse.java`
- [ ] Update `ListingBrowseService` native query to compute and return `ST_Distance`
- [ ] Populate `distanceMeters` in browse response mapper
- [ ] `distanceMeters` is `null` on all non-nearby endpoints (myListings, create, update, etc.)

**Files to change**
```
src/main/java/com/campusplug/api/listings/dto/ListingResponse.java
src/main/java/com/campusplug/api/listings/browse/ListingBrowseService.java
src/main/java/com/campusplug/api/listings/ListingService.java   ← toResponse() null for distanceMeters
```

**SQL pattern in ListingBrowseService**
```sql
SELECT l.*,
       ST_Distance(
           l.geo,
           ST_MakePoint(:lng, :lat)::geography
       ) AS distance_meters
FROM listings l
WHERE l.status = 'ACTIVE'
  AND ST_DWithin(
      l.geo,
      ST_MakePoint(:lng, :lat)::geography,
      :radiusMeters
  )
ORDER BY distance_meters ASC
LIMIT :limit OFFSET :offset
```

**ListingResponse.java change**
```java
public record ListingResponse(
    Long id,
    Long ownerUserId,
    String title,
    Long priceUgx,
    String currency,
    String categoryCode,
    String description,
    String locationText,
    String campus,
    ListingStatus status,
    ListingActions actions,
    Instant createdAt,
    String primaryImageUrl,
    List<ListingImageResponse> images,
    Double distanceMeters   // ← new — null except on nearby queries
) {}
```

**Testing criteria**
- `GET /api/v1/listings/nearby?lat=-0.6089&lng=30.6570&radiusKm=5` → each item has `distanceMeters` as a number
- `GET /api/v1/listings/my` → `distanceMeters` is `null` on all items
- Results are ordered closest first

---

### Phase G2 — Geocoding Proxy Endpoints (Google Maps key stays server-side)

**Goal:** Let Flutter convert a typed address into lat/lng (for listing creation) and convert lat/lng into a human-readable address (for display) — without exposing the Google Maps API key in the APK.

**Why:** The Google Maps Geocoding API key, if placed in Flutter source, can be extracted from a compiled APK using `strings` or `apktool`. Routing through your backend means the key only lives in your `.env` and Render environment.

**Deliverables**
- [ ] Create Google Maps API key (restricted):
  - Go to [console.cloud.google.com](https://console.cloud.google.com)
  - Enable: **Geocoding API**, **Maps SDK for Android**, **Maps SDK for iOS**
  - Create two keys:
    1. **Server key** (no app restriction, restrict to Geocoding API only) → goes in `.env` as `GOOGLE_MAPS_API_KEY`
    2. **Mobile key** (restricted to your app package name) → goes in Flutter `AndroidManifest.xml` for map rendering only — this key cannot geocode
- [ ] Add `GOOGLE_MAPS_API_KEY` to `.env` and `application.yml`
- [ ] Add `RestTemplate` bean to Spring context
- [ ] Create `GeocodingService` — calls `https://maps.googleapis.com/maps/api/geocode/json`
- [ ] Create `GeocodingController` (require JWT):
  - `GET /api/v1/geo/geocode?address=MUST+Mbarara` → `{ lat, lng, formattedAddress }`
  - `GET /api/v1/geo/reverse?lat=-0.6089&lng=30.6570` → `{ address }`

**New package structure**
```
src/main/java/com/campusplug/api/geo/
  GeocodingController.java
  GeocodingService.java
  GeoPoint.java                 ← record(double lat, double lng)
  GeocodeResponse.java          ← record(double lat, double lng, String formattedAddress)
```

**GeocodingService core logic**
```java
@Service
public class GeocodingService {

    @Value("${app.google.maps-api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private static final String BASE = "https://maps.googleapis.com/maps/api/geocode/json";

    public GeocodeResponse geocode(String address) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "GEOCODING_NOT_CONFIGURED", "Google Maps API key not set");
        }
        String url = UriComponentsBuilder.fromHttpUrl(BASE)
            .queryParam("address", address)
            .queryParam("key", apiKey)
            .toUriString();
        // parse results[0].geometry.location + formatted_address
    }

    public String reverseGeocode(double lat, double lng) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE)
            .queryParam("latlng", lat + "," + lng)
            .queryParam("key", apiKey)
            .toUriString();
        // parse results[0].formatted_address
    }
}
```

**application.yml addition**
```yaml
app:
  google:
    maps-api-key: ${GOOGLE_MAPS_API_KEY:}
```

**Environment variables added**
```
GOOGLE_MAPS_API_KEY=AIza...   # server-side geocoding key (stays in .env only)
```

**Testing criteria**
- `GET /api/v1/geo/geocode?address=MUST+Mbarara` → `{ lat, lng, formattedAddress }`
- `GET /api/v1/geo/reverse?lat=-0.6089&lng=30.6570` → `{ address }`
- `GOOGLE_MAPS_API_KEY` never appears in any API response body
- Missing key → `503 GEOCODING_NOT_CONFIGURED`

---

### Phase G3 — Store User Last Known Location

**Goal:** Track where each user is so the API can answer "who is within 5 km of this new listing?" when sending FCM push notifications.

**Why:** Without storing location per user, the push service has no way to know which users to notify.

**Flyway migration `V5__user_last_location.sql`**
```sql
-- Last known location reported by the Flutter app (geofence_service or WorkManager)
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS last_known_lat    DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS last_known_lng    DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS last_location_at  TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_geo          geography(Point, 4326);

-- Spatial index — used by reverse-nearby push query
CREATE INDEX IF NOT EXISTS idx_users_last_geo
  ON users USING GIST(last_geo);

-- FCM token — added here too since it is needed by the push query
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(512);
```

**New endpoint `PUT /api/v1/users/location`**
```
Request (requires JWT):
{
  "lat": -0.6089,
  "lng": 30.6570,
  "event": "ENTERED_CAMPUS"   ← optional: ENTERED_CAMPUS | EXITED_CAMPUS | PERIODIC_UPDATE
}

Response: 200 OK
```

Flutter calls this from:
1. `geofence_service` `ENTER` callback
2. `geofence_service` `EXIT` callback
3. `workmanager` periodic task (every 15 min)
4. App foreground open

**UserRepository method for push queries**
```java
@Query(value = """
    SELECT * FROM users
    WHERE last_geo IS NOT NULL
      AND fcm_token IS NOT NULL
      AND ST_DWithin(last_geo, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
    """, nativeQuery = true)
List<UserEntity> findUsersNearPoint(
    @Param("lat") double lat,
    @Param("lng") double lng,
    @Param("radiusMeters") double radiusMeters
);
```

**Files to change/create**
```
src/main/resources/db/migration/V5__user_last_location.sql   ← new
src/main/java/com/campusplug/api/users/UserEntity.java       ← add last_geo, last_known_lat/lng, fcm_token fields
src/main/java/com/campusplug/api/users/UserRepository.java   ← add findUsersNearPoint()
src/main/java/com/campusplug/api/users/UserService.java      ← add updateLastLocation()
src/main/java/com/campusplug/api/users/UserController.java   ← add PUT /location, PUT /fcm-token
src/main/java/com/campusplug/api/users/dto/UpdateLocationRequest.java  ← new DTO
src/main/java/com/campusplug/api/users/dto/FcmTokenRequest.java        ← new DTO
```

**Testing criteria**
- `PUT /api/v1/users/location` with JWT → 200, `last_geo` + `last_location_at` updated in DB
- `PUT /api/v1/users/location` without JWT → 401
- `PUT /api/v1/users/fcm-token` → 200, token stored
- `findUsersNearPoint()` returns only users with both `last_geo` and `fcm_token` set

---

### Phase G4 — FCM Push Notifications (New Nearby Listing Alert)

**Goal:** When a listing becomes ACTIVE, push a notification to all users within 5 km. Also trigger a push when a user enters campus and new listings have been posted since they were last there.

**Deliverables**

**1. Add Firebase Admin SDK to `pom.xml`**
```xml
<dependency>
  <groupId>com.google.firebase</groupId>
  <artifactId>firebase-admin</artifactId>
  <version>9.2.0</version>
</dependency>
```

**2. Get Firebase service account**
1. Firebase Console → Project Settings → Service Accounts
2. Click **Generate new private key** → download `serviceAccountKey.json`
3. Base64-encode: `base64 -w 0 serviceAccountKey.json` (Linux/Mac) or in PowerShell:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("serviceAccountKey.json"))
   ```
4. Paste result into `.env` as `FIREBASE_SERVICE_ACCOUNT_JSON`

**3. FirebaseConfig.java**
```java
@Configuration
public class FirebaseConfig {

    @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}")
    private String serviceAccountJson;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("FIREBASE_SERVICE_ACCOUNT_JSON not set — FCM push disabled");
            return null;
        }
        byte[] decoded = Base64.getDecoder().decode(serviceAccountJson.trim());
        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(decoded)))
            .build();
        if (FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.initializeApp(options);
        }
        return FirebaseApp.getInstance();
    }
}
```

**4. PushNotificationService.java**
```java
@Service
public class PushNotificationService {

    // Called by ListingService.create() after listing goes ACTIVE
    @Async
    public void notifyNearbyUsers(ListingEntity listing) {
        if (!fcmEnabled()) return;

        List<UserEntity> nearby = userRepository.findUsersNearPoint(
            getLat(listing), getLng(listing), 5000.0);

        for (UserEntity user : nearby) {
            if (user.getFcmToken() == null) continue;
            if (user.getId().equals(listing.getOwnerUserId())) continue; // skip seller

            Message msg = Message.builder()
                .setToken(user.getFcmToken())
                .setNotification(Notification.builder()
                    .setTitle("New listing near you!")
                    .setBody(listing.getTitle() + " — UGX " +
                             formatPrice(listing.getPriceUgx()))
                    .build())
                .putData("listingId", listing.getId().toString())
                .putData("type", "NEW_NEARBY_LISTING")
                .build();

            try {
                FirebaseMessaging.getInstance().send(msg);
            } catch (FirebaseMessagingException e) {
                log.warn("FCM failed for user {}: {}", user.getId(), e.getMessage());
                // If token invalid → clear it from DB
                if ("registration-token-not-registered".equals(e.getErrorCode().name())) {
                    userRepository.clearFcmToken(user.getId());
                }
            }
        }
    }

    // Called by UserService.updateLastLocation() when event = ENTERED_CAMPUS
    @Async
    public void notifyUserEnteredCampus(Long userId, double lat, double lng) {
        // Find listings posted within 2km in last 24h that user hasn't seen
        // Send: "3 new listings near MUST campus since you were last here"
    }
}
```

**5. Hook into ListingService.create()**
```java
// After saved.setStatus(ACTIVE) and listingRepository.save(saved):
pushNotificationService.notifyNearbyUsers(saved); // returns immediately (@Async)
```

**Environment variables added**
```
FIREBASE_SERVICE_ACCOUNT_JSON=<base64 of serviceAccountKey.json>
```

**Testing criteria**
- New listing created → FCM message visible in Firebase Console → Logs
- Seller does NOT receive push for their own listing
- Users with `fcm_token = null` silently skipped
- Invalid/expired token → token cleared from DB
- Listing creation response time unaffected (`@Async`)

---

### Phase G5 — Native Geofencing (geofence_service + WorkManager) — Flutter Only

**Goal:** Detect user entering/exiting the MUST campus geofence in the background and call your backend. No external geofencing service. No webhook. Flutter talks directly to your API.

**No backend changes needed in this phase** — all backend endpoints were added in G3.

---

#### Part A — Primary: `geofence_service` (native OS geofencing)

**How it works:**
- Registers a geofence circle with Android `GeofencingClient` / iOS `CLLocationManager`
- OS monitors the boundary at the hardware level — extremely battery efficient
- Fires a Dart callback when the boundary is crossed
- Works in foreground and background (not when app is fully killed on aggressive OEMs)

```dart
// geofence_manager.dart

class GeofenceManager {
  static const _mustLat = -0.6089;
  static const _mustLng = 30.6570;

  final _service = GeofenceService.instance.setup(
    interval: 5000,             // re-check every 5s while active
    accuracy: 100,              // 100m GPS accuracy threshold
    loiteringDelayMs: 60000,    // 1 min inside before DWELL fires
    statusChangeDelayMs: 10000, // 10s debounce — avoids rapid enter/exit flicker
    useActivityRecognition: true,
    allowMockLocations: false,
  );

  final _geofences = [
    Geofence(
      id: 'must_campus',
      latitude: _mustLat,
      longitude: _mustLng,
      radius: [GeofenceRadius(id: 'r_1km', length: 1000)],
    ),
  ];

  Future<void> start() async {
    _service.addGeofenceStatusChangeListener(_onStatusChange);
    _service.addStreamErrorListener((e) => debugPrint('Geofence error: $e'));
    await _service.start(_geofences).catchError((e) => debugPrint('Start error: $e'));
  }

  Future<void> _onStatusChange(
    Geofence geofence,
    GeofenceRadius radius,
    GeofenceStatus status,
    Location location,
  ) async {
    final event = switch (status) {
      GeofenceStatus.ENTER => 'ENTERED_CAMPUS',
      GeofenceStatus.EXIT  => 'EXITED_CAMPUS',
      GeofenceStatus.DWELL => 'PERIODIC_UPDATE',
      _ => 'PERIODIC_UPDATE',
    };

    await ApiService.instance.put('/api/v1/users/location', {
      'lat': location.latitude,
      'lng': location.longitude,
      'event': event,
    });
  }
}
```

**Call `start()` in `main.dart`:**
```dart
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  await GeofenceManager().start();
  runApp(const CampusPlugApp());
}
```

**Android `AndroidManifest.xml` additions:**
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>

<application ...>
  <service
    android:name="com.pravera.flutter_geofence_service.service.GeofenceService"
    android:foregroundServiceType="location"
    android:exported="false"/>
</application>
```

**iOS `Info.plist` additions:**
```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>CampusPlug needs your location to show nearby listings</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>CampusPlug monitors when you enter campus to alert you about new listings</string>
<key>NSLocationAlwaysUsageDescription</key>
<string>CampusPlug monitors campus entry in the background</string>
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
  <string>fetch</string>
</array>
```

---

#### Part B — Fallback: `workmanager` (when app is killed)

**How it works:**
- Registered Android `JobScheduler` / iOS `BGTaskScheduler`
- Survives app kill — OS relaunches a minimal Dart isolate
- Fires every 15 minutes minimum (OS enforced — cannot be shorter)
- Reads last geofence state from `SharedPreferences`, compares to current location, reports changes

```dart
// background_tasks.dart

// Top-level function — runs in a separate Dart isolate (no Flutter widgets)
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((taskName, _) async {
    if (taskName == 'campusGeofenceCheck') {
      await _runGeofenceCheck();
    }
    return true; // always true — returning false causes OS to stop retrying
  });
}

Future<void> _runGeofenceCheck() async {
  try {
    // 1. Check permission — if denied, exit silently
    final permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) return;

    // 2. Get current position
    final pos = await Geolocator.getCurrentPosition(
      desiredAccuracy: LocationAccuracy.balanced, // saves battery
    );

    // 3. Calculate distance to MUST campus
    final distanceM = Geolocator.distanceBetween(
      pos.latitude, pos.longitude,
      -0.6089, 30.6570,
    );
    final isInsideNow = distanceM <= 1000.0; // 1km radius

    // 4. Load persisted state from last run
    final prefs    = await SharedPreferences.getInstance();
    final wasBefore = prefs.getBool('campusInside') ?? false;
    final baseUrl   = prefs.getString('apiBaseUrl') ?? '';
    final jwt       = prefs.getString('jwtToken') ?? '';

    if (jwt.isEmpty || baseUrl.isEmpty) return; // not logged in

    // 5. Determine event type
    final event = (isInsideNow && !wasBefore) ? 'ENTERED_CAMPUS'
                : (!isInsideNow && wasBefore) ? 'EXITED_CAMPUS'
                : 'PERIODIC_UPDATE';

    // 6. Report to your API
    await http.put(
      Uri.parse('$baseUrl/api/v1/users/location'),
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer $jwt',
      },
      body: jsonEncode({
        'lat': pos.latitude,
        'lng': pos.longitude,
        'event': event,
      }),
    );

    // 7. Persist new state for next run
    await prefs.setBool('campusInside', isInsideNow);

  } catch (_) {
    // Silent fail — next run in 15 min will retry
    // Never rethrow here
  }
}
```

**Register in `main.dart`:**
```dart
// After Firebase.initializeApp():
await Workmanager().initialize(callbackDispatcher, isInDebugMode: false);

await Workmanager().registerPeriodicTask(
  'campusGeofenceCheck',
  'campusGeofenceCheck',
  frequency: const Duration(minutes: 15),
  constraints: Constraints(networkType: NetworkType.connected),
  existingWorkPolicy: ExistingWorkPolicy.keep,
);
```

**Save credentials on login (needed inside background isolate):**
```dart
// In your login success handler:
final prefs = await SharedPreferences.getInstance();
await prefs.setString('jwtToken', loginResponse.token);
await prefs.setString('apiBaseUrl', 'https://your-api.onrender.com');
```

**Clear credentials on logout:**
```dart
await prefs.remove('jwtToken');
await prefs.remove('campusInside');
```

---

#### Part C — Location Permission Request Flow

```dart
// location_permission.dart

Future<bool> requestLocationPermissions(BuildContext context) async {
  // Step 1: check current state
  LocationPermission perm = await Geolocator.checkPermission();

  // Step 2: request foreground if not granted
  if (perm == LocationPermission.denied) {
    perm = await Geolocator.requestPermission();
  }

  if (perm == LocationPermission.deniedForever) {
    // Show "Go to Settings" dialog
    return false;
  }

  // Step 3: on Android 10+, request background separately
  // (must be AFTER foreground permission is granted)
  // Show explanation dialog first: "To alert you when you enter campus..."
  // Then call requestPermission() again — Android shows system background dialog
  if (perm == LocationPermission.whileInUse) {
    // show rationale dialog, then:
    perm = await Geolocator.requestPermission();
  }

  return perm == LocationPermission.always ||
         perm == LocationPermission.whileInUse;
}
```

**Testing criteria for Phase G5**
- App foreground: cross 1km campus boundary → `PUT /api/v1/users/location` called within 10 seconds
- App background (not killed): cross boundary → called within 30 seconds
- App killed: WorkManager fires within 15 minutes → location updated in DB
- Rapid enter/exit near boundary → `statusChangeDelayMs: 10000` debounces false triggers
- Aggressive OEM battery killer (Samsung/Xiaomi): WorkManager still fires (registered with JobScheduler)

---

### Phase G6 — Postman Collection + Environment Update

**Goal:** All new GIS endpoints documented, testable, and asserting correct responses in Postman.

**New folder "GIS / Location" in collection:**

| Request | Method | URL | Notes |
|---|---|---|---|
| Update Location (campus enter) | PUT | `/api/v1/users/location` | body: `{lat, lng, event: "ENTERED_CAMPUS"}` |
| Update Location (periodic) | PUT | `/api/v1/users/location` | body: `{lat, lng, event: "PERIODIC_UPDATE"}` |
| Register FCM Token | PUT | `/api/v1/users/fcm-token` | body: `{token: "..."}` |
| Geocode Address | GET | `/api/v1/geo/geocode?address=MUST+Mbarara` | asserts lat/lng returned |
| Reverse Geocode | GET | `/api/v1/geo/reverse?lat=-0.6089&lng=30.6570` | asserts address string |
| Nearby Listings | GET | `/api/v1/listings/nearby?lat=-0.6089&lng=30.6570&radiusKm=5` | asserts distanceMeters |

**Test script on Nearby Listings:**
```javascript
pm.test('200 OK', () => pm.response.to.have.status(200));
pm.test('items is array', () => {
  pm.expect(pm.response.json().items).to.be.an('array');
});
pm.test('each item has distanceMeters', () => {
  const items = pm.response.json().items || [];
  items.forEach(item => pm.expect(item.distanceMeters).to.be.a('number'));
});
pm.test('ordered closest first', () => {
  const items = pm.response.json().items || [];
  for (let i = 1; i < items.length; i++) {
    pm.expect(items[i].distanceMeters).to.be.gte(items[i-1].distanceMeters);
  }
});
```

**New env vars:**
- `userLat` → `-0.6089`
- `userLng` → `30.6570`
- `fcmToken` → paste from Firebase Console for testing

---

## Dependency Order

```
G1 — distanceMeters in nearby response
       ↓ (no external deps — do first)
G2 — geocoding proxy
       ↓ (needs GOOGLE_MAPS_API_KEY from Google Cloud Console)
G3 — user last location + fcm token (V5 migration)
       ↓ (unblocks G4)
G4 — FCM push (needs G3 + Firebase project)
       ↓ (G3 endpoint must exist before Flutter can call it)
G5 — Native geofencing in Flutter (geofence_service + workmanager)
       ↓
G6 — Postman collection update
```

---

## Environment Variables — Full GIS Additions

| Variable | Where to get it | Phase |
|---|---|---|
| `GOOGLE_MAPS_API_KEY` | Google Cloud Console → APIs & Services → Credentials | G2 |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase Console → Project Settings → Service Accounts → Generate key → base64 encode | G4 |

Add both to:
- `.env` (local dev)
- Render dashboard → Environment (production)

**No Radar.io / BlueDot.io keys needed** — geofencing is handled natively by the device OS.

---

## Flutter Integration Checklist

| Flutter task | Backend phase needed | Flutter package |
|---|---|---|
| Show listings on Google Maps as pins | G1 | `google_maps_flutter` |
| Show "X km away" on listing card | G1 | uses `distanceMeters` from API |
| Let user pick location on map | G2 | `google_maps_flutter` |
| Reverse geocode current position to address | G2 | calls `/api/v1/geo/reverse` |
| Update user location on app open | G3 | `geolocator` |
| Register FCM token on startup | G4 | `firebase_messaging` |
| Receive push "new listing near you" | G4 | `firebase_messaging` |
| Native geofence enter/exit (foreground + background) | G3 endpoint | `geofence_service` |
| Fallback when app killed | G3 endpoint | `workmanager` |
| Request background location permission | G5 | `geolocator` |
| Persist JWT for WorkManager isolate | G5 | `shared_preferences` |

---

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Google Maps geocoding key extracted from APK | Two separate keys: server key (geocoding, in `.env` only) + mobile key (rendering only, restricted to package name in Google Cloud Console) |
| FCM push slows listing creation | `@Async` on `notifyNearbyUsers()` — seller gets response in <100ms, push runs in background thread |
| `geofence_service` killed by aggressive battery saver (Samsung, Xiaomi, Huawei) | WorkManager fallback fires every 15 min regardless; also prompt user to disable battery optimization on first launch |
| WorkManager 15-min minimum = delayed notification | Acceptable — "someone posted 15 min ago near you" is still useful. For real-time, `geofence_service` covers it while app is alive |
| PostGIS nearby query slow at scale | GIST index on `geo` column already exists from V2 migration — `ST_DWithin` uses it automatically |
| User denies background location permission | Fall back to campus-level location from user profile `registeredLocation` lat/lng for push targeting |
| iOS restricts background location | `geofence_service` uses `CLLocationManager` region monitoring which is the iOS-approved background location method; declare `location` in `UIBackgroundModes` |
| JWT expires inside WorkManager background isolate | WorkManager reads JWT from `SharedPreferences`; if expired → `401` caught silently → user re-logs in on next app open which refreshes the stored JWT |
| Render free tier cold start when notification fires | Use UptimeRobot (free) to ping `/actuator/health` every 5 min and keep instance warm |
| Stale FCM tokens (device reset, reinstall) | `PushNotificationService` catches `registration-token-not-registered` error and clears token from DB |
