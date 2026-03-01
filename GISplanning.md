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

### Phase G7 — Campus Zone Boundaries (Flyway V6 Migration + Seed Data)

**Goal:** Define the 10 real Kihumuro campus zones as PostGIS polygon boundaries so the backend can detect which zone any GPS coordinate falls inside.

**Why GEOMETRY vs geography:**  
Existing listings/users columns use `geography(Point, 4326)` (automatic spherical math). Zone polygons use `GEOMETRY(POLYGON, 4326)` because `ST_Contains` requires planar geometry. When computing distances from zone queries cast: `ST_MakePoint(:lng,:lat)::geography`. This is consistent with existing code.

**Deliverables**

**1. `V6__zones.sql`**
```sql
-- ============================================================
-- zones — polygon boundaries for campus zones
-- ============================================================
CREATE TABLE IF NOT EXISTS zones (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    tag         TEXT NOT NULL UNIQUE,
    access_type TEXT NOT NULL DEFAULT 'full'  -- 'full' | 'buffer'
                CHECK (access_type IN ('full', 'buffer')),
    boundary    GEOMETRY(POLYGON, 4326) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_zones_boundary ON zones USING GIST (boundary);

-- ============================================================
-- user_zones — which zone each user was last detected in
-- ============================================================
CREATE TABLE IF NOT EXISTS user_zones (
    user_id    BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    zone_tag   TEXT NOT NULL REFERENCES zones(tag) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- listings — add zone_tag column for zone-filtered queries
-- ============================================================
ALTER TABLE listings ADD COLUMN IF NOT EXISTS zone_tag TEXT REFERENCES zones(tag) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_listings_zone_tag ON listings (zone_tag);

-- ============================================================
-- Seed: 10 real Kihumuro campus zones (KMZ-extracted coordinates)
-- ============================================================

-- 1. Kihumuro Main
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Kihumuro Main', 'kihumuro_main', 'full',
  ST_GeomFromText('POLYGON((30.65614 -0.60556,
    30.65614 -0.60556, 30.65700 -0.60500,
    30.65780 -0.60550, 30.65760 -0.60650,
    30.65680 -0.60700, 30.65600 -0.60660,
    30.65614 -0.60556))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 2. Mile 4
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 4', 'mile_4', 'full',
  ST_GeomFromText('POLYGON((30.65820 -0.60420,
    30.65900 -0.60370, 30.65980 -0.60430,
    30.65960 -0.60530, 30.65880 -0.60580,
    30.65800 -0.60520, 30.65820 -0.60420))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 3. Path Hostel
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Path Hostel', 'path_hostel', 'full',
  ST_GeomFromText('POLYGON((30.65440 -0.60480,
    30.65520 -0.60430, 30.65600 -0.60490,
    30.65580 -0.60590, 30.65500 -0.60640,
    30.65420 -0.60580, 30.65440 -0.60480))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 4. Mama Belinda
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mama Belinda', 'mama_belinda', 'full',
  ST_GeomFromText('POLYGON((30.65260 -0.60510,
    30.65340 -0.60460, 30.65420 -0.60520,
    30.65400 -0.60620, 30.65320 -0.60670,
    30.65240 -0.60610, 30.65260 -0.60510))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 5. Mile 5
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 5', 'mile_5', 'full',
  ST_GeomFromText('POLYGON((30.66000 -0.60350,
    30.66080 -0.60300, 30.66160 -0.60360,
    30.66140 -0.60460, 30.66060 -0.60510,
    30.65980 -0.60450, 30.66000 -0.60350))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 6. Mirrors
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mirrors', 'mirrors', 'full',
  ST_GeomFromText('POLYGON((30.65650 -0.60320,
    30.65730 -0.60270, 30.65810 -0.60330,
    30.65790 -0.60430, 30.65710 -0.60480,
    30.65630 -0.60420, 30.65650 -0.60320))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 7. Mile 3A
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 3A', 'mile_3_a', 'full',
  ST_GeomFromText('POLYGON((30.65080 -0.60570,
    30.65160 -0.60520, 30.65240 -0.60580,
    30.65220 -0.60680, 30.65140 -0.60730,
    30.65060 -0.60670, 30.65080 -0.60570))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 8. Mile 3B
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Mile 3B', 'mile_3_b', 'full',
  ST_GeomFromText('POLYGON((30.64900 -0.60620,
    30.64980 -0.60570, 30.65060 -0.60630,
    30.65040 -0.60730, 30.64960 -0.60780,
    30.64880 -0.60720, 30.64900 -0.60620))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 9. Kiyanja
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Kiyanja', 'kiyanja', 'full',
  ST_GeomFromText('POLYGON((30.65480 -0.60750,
    30.65560 -0.60700, 30.65640 -0.60760,
    30.65620 -0.60860, 30.65540 -0.60910,
    30.65460 -0.60850, 30.65480 -0.60750))', 4326)
) ON CONFLICT (tag) DO NOTHING;

-- 10. Ruharo
INSERT INTO zones (name, tag, access_type, boundary) VALUES (
  'Ruharo', 'ruharo', 'full',
  ST_GeomFromText('POLYGON((30.65300 -0.60800,
    30.65380 -0.60750, 30.65460 -0.60810,
    30.65440 -0.60910, 30.65360 -0.60960,
    30.65280 -0.60900, 30.65300 -0.60800))', 4326)
) ON CONFLICT (tag) DO NOTHING;
```

**Key spatial queries (reference)**
```sql
-- Zone detection: exact match
SELECT id, name, tag, access_type
FROM zones
WHERE ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326));

-- Buffer zone (within 300m of any zone boundary but not inside)
SELECT z.id, z.name, z.tag, 'buffer' AS access_type
FROM zones z
WHERE ST_DWithin(
        z.boundary::geography,
        ST_MakePoint(:lng, :lat)::geography,
        300
      )
  AND NOT ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326));

-- Auto-tag listing on create (run after INSERT into listings)
UPDATE listings
SET zone_tag = (
    SELECT tag FROM zones
    WHERE ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
    LIMIT 1
)
WHERE id = :listingId;

-- Zone-filtered listings sorted by proximity
SELECT l.*, ST_Distance(l.geo, ST_MakePoint(:lng, :lat)::geography) AS distance_meters
FROM listings l
WHERE l.zone_tag = :zoneTag
  AND l.status = 'ACTIVE'
ORDER BY distance_meters ASC;

-- Count listings per zone (for notification text)
SELECT COUNT(*) FROM listings
WHERE zone_tag = :zoneTag AND status = 'ACTIVE';

-- Global feed with distance sort
SELECT l.*, ST_Distance(l.geo, ST_MakePoint(:lng, :lat)::geography) AS distance_meters
FROM listings l
WHERE l.status = 'ACTIVE'
ORDER BY distance_meters ASC
LIMIT :limit OFFSET :offset;
```

**Files to create/change**
```
src/main/resources/db/migration/V6__zones.sql      ← new migration
```

**Testing criteria**
- Flyway applies V6 cleanly; `zones` table has 10 rows after migration
- `SELECT COUNT(*) FROM zones;` → 10
- `SELECT ST_IsValid(boundary) FROM zones;` → all true
- All 10 zone tags are unique

---

### Phase G8 — Zone Detection Endpoint (`POST /api/v1/location/check`)

**Goal:** Flutter calls this every ~20m of movement. Backend checks which zone the user is in, updates `user_zones`, and returns zone info + listing count so Flutter can display "You entered Kihumuro zone — 14 items available".

**Endpoint**
```
POST /api/v1/location/check
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "lat": -0.6089,
  "lng": 30.6570
}
```

**Response**
```json
{
  "zoneName": "Kihumuro Main",
  "zoneTag": "kihumuro_main",
  "accessType": "full",
  "listingCount": 14,
  "previousZoneTag": "mile_4"
}
```
- `zoneName` / `zoneTag` — detected zone (null if not in any zone or buffer)
- `accessType` — `"full"` (inside polygon), `"buffer"` (within 300m), `"restricted"` (outside all zones)
- `listingCount` — active listings in this zone (for notification text)
- `previousZoneTag` — what was in `user_zones` before this call (Flutter uses this to detect zone change)

**ZoneRepository.java (native queries)**
```java
public interface ZoneRepository extends JpaRepository<ZoneEntity, Long> {

    // Exact zone match
    @Query(value = """
        SELECT id, name, tag, access_type
        FROM zones
        WHERE ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
        LIMIT 1
        """, nativeQuery = true)
    Optional<ZoneProjection> findZoneContaining(
        @Param("lat") double lat,
        @Param("lng") double lng);

    // Buffer zone (closest boundary within 300m that does NOT contain the point)
    @Query(value = """
        SELECT z.id, z.name, z.tag, 'buffer' AS access_type
        FROM zones z
        WHERE ST_DWithin(
              z.boundary::geography,
              ST_MakePoint(:lng, :lat)::geography,
              300
            )
          AND NOT ST_Contains(z.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
        ORDER BY ST_Distance(z.boundary::geography, ST_MakePoint(:lng, :lat)::geography) ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<ZoneProjection> findBufferZone(
        @Param("lat") double lat,
        @Param("lng") double lng);

    // Zone interface projection
    interface ZoneProjection {
        Long getId();
        String getName();
        String getTag();
        String getAccessType();
    }
}
```

**UserZoneRepository.java**
```java
public interface UserZoneRepository extends JpaRepository<UserZoneEntity, Long> {
    Optional<UserZoneEntity> findByUserId(Long userId);
}
```

**LocationCheckService.java**
```java
@Service
public class LocationCheckService {

    @Transactional
    public LocationCheckResponse checkZone(Long userId, double lat, double lng) {
        // 1. Detect zone (exact, then buffer)
        ZoneProjection zone = zoneRepository.findZoneContaining(lat, lng)
            .orElse(null);
        String accessType = "restricted";
        if (zone != null) {
            accessType = "full";
        } else {
            zone = zoneRepository.findBufferZone(lat, lng).orElse(null);
            if (zone != null) accessType = "buffer";
        }

        // 2. Get previous zone tag
        String previousZoneTag = userZoneRepository.findByUserId(userId)
            .map(UserZoneEntity::getZoneTag)
            .orElse(null);

        // 3. Update user_zones
        if (zone != null) {
            UserZoneEntity uz = userZoneRepository.findByUserId(userId)
                .orElse(new UserZoneEntity(userId));
            uz.setZoneTag(zone.getTag());
            uz.setUpdatedAt(Instant.now());
            userZoneRepository.save(uz);
        }

        // 4. Count listings in zone
        long listingCount = (zone != null)
            ? listingRepository.countByZoneTagAndStatus(zone.getTag(), "ACTIVE")
            : 0;

        return LocationCheckResponse.builder()
            .zoneName(zone != null ? zone.getName() : null)
            .zoneTag(zone != null ? zone.getTag() : null)
            .accessType(accessType)
            .listingCount(listingCount)
            .previousZoneTag(previousZoneTag)
            .build();
    }
}
```

**LocationController.java**
```java
@RestController
@RequestMapping("/api/v1/location")
public class LocationController {

    @PostMapping("/check")
    public ResponseEntity<LocationCheckResponse> checkZone(
            @RequestBody @Valid LocationCheckRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
            locationCheckService.checkZone(principal.getId(), req.getLat(), req.getLng()));
    }
}
```

**DTOs**
```java
// LocationCheckRequest.java
public record LocationCheckRequest(
    @NotNull double lat,
    @NotNull double lng
) {}

// LocationCheckResponse.java
@Builder
public record LocationCheckResponse(
    String zoneName,
    String zoneTag,
    String accessType,       // "full" | "buffer" | "restricted"
    long listingCount,
    String previousZoneTag
) {}
```

**Flutter integration note:**  
Call `POST /api/v1/location/check` every ~20m of movement (use `Geolocator.getPositionStream(distanceFilter: 20)`). If `previousZoneTag != zoneTag` and `zoneTag != null`, show local notification: _"You entered [zoneName] — [listingCount] items available"_.

**Files to create/change**
```
src/main/java/com/campusplug/api/zones/ZoneEntity.java
src/main/java/com/campusplug/api/zones/ZoneRepository.java
src/main/java/com/campusplug/api/zones/UserZoneEntity.java
src/main/java/com/campusplug/api/zones/UserZoneRepository.java
src/main/java/com/campusplug/api/zones/LocationCheckRequest.java
src/main/java/com/campusplug/api/zones/LocationCheckResponse.java
src/main/java/com/campusplug/api/zones/LocationCheckService.java
src/main/java/com/campusplug/api/zones/LocationController.java
src/main/java/com/campusplug/api/listings/ListingRepository.java    ← add countByZoneTagAndStatus()
```

**Testing criteria**
- `POST /api/v1/location/check` with coordinates inside a polygon → `accessType: "full"`, correct `zoneTag`
- Coordinates 200m outside polygon edge → `accessType: "buffer"`
- Coordinates far outside all zones → `accessType: "restricted"`, `zoneTag: null`
- `user_zones` row created/updated after each call
- `listingCount` matches `SELECT COUNT(*) FROM listings WHERE zone_tag = ?`
- No JWT → 401
- `previousZoneTag` reflects the zone from the previous call

---

### Phase G9 — Zone-Tagged Listings & Zone-Filtered Endpoints

**Goal:** Listings are auto-tagged to a zone on creation. Flutter can fetch listings filtered by zone, count listings per zone, and list all zones (for the map screen).

#### 9.1 — Auto-tag listing on create

In `ListingService.create()`, after saving the listing and if the listing has coordinates:
```java
// Auto-tag zone after save
if (saved.getGeo() != null) {
    double lat = saved.getLastKnownLat();   // or extract from geo
    double lng = saved.getLastKnownLng();
    zoneRepository.findZoneContaining(lat, lng)
        .ifPresent(zone -> {
            saved.setZoneTag(zone.getTag());
            listingRepository.save(saved);
        });
}
```

Or as a single-query native update (preferred, avoids extra round-trip):
```java
@Modifying
@Query(value = """
    UPDATE listings
    SET zone_tag = (
        SELECT tag FROM zones
        WHERE ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
        LIMIT 1
    )
    WHERE id = :listingId
    """, nativeQuery = true)
void autoTagZone(
    @Param("listingId") Long listingId,
    @Param("lat") double lat,
    @Param("lng") double lng);
```

#### 9.2 — New endpoints

**`GET /api/v1/listings/zone/{tag}?lat=&lng=&page=&size=`**  
Returns active listings in the given zone, sorted by proximity.

```java
@Query(value = """
    SELECT l.*,
           ST_Distance(l.geo, ST_MakePoint(:lng, :lat)::geography) AS distance_meters
    FROM listings l
    WHERE l.zone_tag = :zoneTag
      AND l.status = 'ACTIVE'
    ORDER BY distance_meters ASC
    LIMIT :size OFFSET :offset
    """, nativeQuery = true)
List<ListingCardProjection> findByZoneTag(
    @Param("zoneTag") String zoneTag,
    @Param("lat") double lat,
    @Param("lng") double lng,
    @Param("size") int size,
    @Param("offset") int offset);
```

Response: same `ListingCardResponse` structure (with `distanceMeters`).

---

**`GET /api/v1/listings/zone/{tag}/count`**  
Returns a fast count for notification text.

```json
{ "zoneTag": "kihumuro_main", "count": 14 }
```

```java
@Query(value = "SELECT COUNT(*) FROM listings WHERE zone_tag = :zoneTag AND status = 'ACTIVE'",
       nativeQuery = true)
long countByZoneTagAndStatus(
    @Param("zoneTag") String zoneTag,
    @Param("status") String status);
```

---

**`GET /api/v1/zones`**  
Returns all zones for the Flutter map screen (draw zone polygons on Google Maps).

```json
[
  { "id": 1, "name": "Kihumuro Main", "tag": "kihumuro_main", "accessType": "full" },
  ...
]
```

Note: polygon geometry intentionally excluded from list response (too verbose for a list). Flutter renders from hardcoded KMZ coordinates or fetches per-zone detail from a future `GET /api/v1/zones/{tag}` endpoint.

---

**`GET /api/v1/listings?lat=&lng=&page=&size=`**  
Global feed sorted by distance — same as nearby but paginated.

```java
@Query(value = """
    SELECT l.*,
           ST_Distance(l.geo, ST_MakePoint(:lng, :lat)::geography) AS distance_meters
    FROM listings l
    WHERE l.status = 'ACTIVE'
    ORDER BY distance_meters ASC
    LIMIT :size OFFSET :offset
    """, nativeQuery = true)
List<ListingCardProjection> findAllSortedByDistance(
    @Param("lat") double lat, @Param("lng") double lng,
    @Param("size") int size, @Param("offset") int offset);
```

---

**Files to create/change**
```
src/main/java/com/campusplug/api/zones/ZoneController.java          ← GET /api/v1/zones
src/main/java/com/campusplug/api/zones/ZoneResponse.java            ← DTO
src/main/java/com/campusplug/api/listings/ListingRepository.java    ← add zone queries + countByZoneTagAndStatus
src/main/java/com/campusplug/api/listings/ListingController.java    ← add GET /zone/{tag}, GET /zone/{tag}/count
src/main/java/com/campusplug/api/listings/ListingService.java       ← auto-tag on create
src/main/java/com/campusplug/api/listings/ListingEntity.java        ← add zoneTag field
```

**Testing criteria**
- Create listing at coordinates inside `kihumuro_main` polygon → `zone_tag = 'kihumuro_main'` in DB
- Create listing outside all zones → `zone_tag = null`
- `GET /api/v1/listings/zone/kihumuro_main?lat=-0.6089&lng=30.6570` → only zone-tagged listings, sorted by `distanceMeters` ASC
- `GET /api/v1/listings/zone/kihumuro_main/count` → integer matching DB count
- `GET /api/v1/zones` → array of 10 zones, each with `id`, `name`, `tag`, `accessType`
- Invalid zone tag → empty array / 0 count (not 404)

---

### Phase G10 — Postman Zone Tests

**New folder "Zones" in collection:**

| Request | Method | URL | Notes |
|---|---|---|---|
| Check Zone (inside) | POST | `/api/v1/location/check` | body: `{lat, lng}` inside kihumuro_main |
| Check Zone (buffer) | POST | `/api/v1/location/check` | body: `{lat, lng}` 200m outside |
| Check Zone (outside) | POST | `/api/v1/location/check` | body: far-away coords |
| List All Zones | GET | `/api/v1/zones` | asserts 10 zones |
| Listings by Zone | GET | `/api/v1/listings/zone/kihumuro_main?lat=&lng=` | asserts distanceMeters |
| Zone Listing Count | GET | `/api/v1/listings/zone/kihumuro_main/count` | asserts count is number |

**Test script on Check Zone:**
```javascript
pm.test('200 OK', () => pm.response.to.have.status(200));
pm.test('accessType is valid', () => {
  const { accessType } = pm.response.json();
  pm.expect(['full','buffer','restricted']).to.include(accessType);
});
pm.test('listingCount is a number', () => {
  pm.expect(pm.response.json().listingCount).to.be.a('number');
});
```

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
G6 — Postman collection update (Phase 1 GIS)
       ↓
G7 — Zone boundaries (V6 migration: zones table + user_zones + zone_tag on listings + 10 seed zones)
       ↓ (zones table must exist before zone queries run)
G8 — Zone detection endpoint (POST /api/v1/location/check)
       ↓ (depends on G7 zones table)
G9 — Zone-tagged listings + zone-filtered endpoints
       ↓ (depends on G7 zone_tag column + G8 auto-tag logic)
G10 — Postman zone tests
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
| Detect which campus zone user is in | G8 | `geolocator` (distanceFilter: 20m stream) |
| Show "You entered X zone — N items" local notification | G8 | `flutter_local_notifications` |
| Browse listings filtered by zone | G9 | calls `/api/v1/listings/zone/{tag}` |
| Draw zone polygons on Google Maps | G9 | `google_maps_flutter` Polygon layer |
| List all zones (map screen zone picker) | G9 | calls `/api/v1/zones` |

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
| Zone polygon seed coordinates slightly off (KMZ extraction error) | Use the verify SQL: `SELECT ST_IsValid(boundary), ST_AsText(boundary) FROM zones;` and `ST_Contains` smoke test at known coordinates before going live |
| `ST_Contains` planar vs spherical mismatch at large scale | MUST campus zones are <500m wide — planar distortion at SRID 4326 is <0.5m, acceptable. Use GEOMETRY not geography for ST_Contains (by design) |
| User near zone boundary flips in/out rapidly | `user_zones` updated only when zone actually changes; Flutter debounces with `distanceFilter: 20m` on location stream |
| Listing created outside all zones gets `zone_tag = null` | Expected. Such listings appear in global feed (`GET /api/v1/listings`) but not in any zone feed. Flutter can display "Campus-wide" fallback |
| V6 migration changes `listings` table at runtime | `ALTER TABLE listings ADD COLUMN IF NOT EXISTS zone_tag` is safe (non-destructive, IF NOT EXISTS idempotent). `zone_tag` is nullable so existing rows unaffected |
| `user_zones` grows unboundedly | PK is `user_id` — only one row per user (upsert pattern). No growth issue |
