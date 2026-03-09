# CampusPlug API — Flutter Integration Guide

> **Backend**: Spring Boot 3 · PostgreSQL + PostGIS · Redis · Cloudinary · Firebase FCM  
> **Auth model**: Stateless JWT (HMAC256) — `Authorization: Bearer <token>` on every authenticated request  
> **Base URL** (local): `http://10.0.2.2:8080` (Android emulator) or `http://localhost:8080`  
> **Base URL** (production): `https://campusplug-api.onrender.com`  
> **API version prefix**: `/api/v1`

---

## Table of Contents

0. [Health & Actuator Endpoints](#0-health--actuator-endpoints)
1. [Project Setup](#1-project-setup)
2. [HTTP Client (Dio)](#2-http-client-dio)
3. [Authentication & Token Management](#3-authentication--token-management)
4. [Error Handling](#4-error-handling)
5. [Auth Endpoints](#5-auth-endpoints)
6. [Users Endpoints](#6-users-endpoints)
7. [Listings — Owner Actions](#7-listings--owner-actions)
8. [Listings — Browse / Public](#8-listings--browse--public)
9. [Categories Endpoints](#9-categories-endpoints)
10. [Bookmarks Endpoints](#10-bookmarks-endpoints)
11. [Conversations Endpoints](#11-conversations-endpoints)
12. [Messages Endpoints](#12-messages-endpoints)
13. [Image Uploads (Cloudinary)](#13-image-uploads-cloudinary)
14. [Geo / Geocoding Endpoints](#14-geo--geocoding-endpoints)
15. [Zones Endpoints](#15-zones-endpoints)
16. [Location Check Endpoint](#16-location-check-endpoint)
17. [WebSocket / Real-time Messaging](#17-websocket--real-time-messaging)
18. [FCM Push Notifications](#18-fcm-push-notifications)
19. [Environment & Configuration](#19-environment--configuration)
20. [Complete Dart Model Classes](#20-complete-dart-model-classes)
21. [Full Buyer–Seller Conversation Walkthrough](#21-full-buyerseller-conversation-walkthrough)

---

## 0. Health & Actuator Endpoints

> These endpoints are for **backend/ops monitoring only** — the Flutter frontend does not need to call them directly.

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/` | No | Returns service name, status, timestamp |
| `GET` | `/actuator/health` | No | Returns `{ "status": "UP" }` |
| `GET` | `/actuator/info` | **Yes** | Build info, version metadata |

**`GET /`** sample response
```json
{ "service": "campusplug-api", "status": "UP", "timestamp": "2026-03-07T10:00:00Z" }
```

**`GET /actuator/health`** sample response
```json
{ "status": "UP" }
```

---

## 1. Project Setup

### 1.1 pubspec.yaml dependencies

```yaml
dependencies:
  flutter:
    sdk: flutter

  # HTTP client
  dio: ^5.4.0

  # Secure token storage
  flutter_secure_storage: ^9.0.0

  # WebSocket / STOMP messaging
  stomp_dart_client: ^1.1.0

  # Firebase (FCM push notifications)
  firebase_core: ^2.27.0
  firebase_messaging: ^14.7.18

  # Image picking & upload
  image_picker: ^1.0.7

  # Local notifications
  flutter_local_notifications: ^17.0.0

  # Geolocation
  geolocator: ^11.0.0

  # JSON serialization
  json_annotation: ^4.8.1

dev_dependencies:
  build_runner: ^2.4.8
  json_serializable: ^6.7.1
```

### 1.2 Android permissions (android/app/src/main/AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.VIBRATE" />
```

### 1.3 iOS permissions (ios/Runner/Info.plist)

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>CampusPlug uses your location to show nearby listings.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>CampusPlug uses your background location for campus zone detection.</string>
```

---

## 2. HTTP Client (Dio)

Create a single Dio instance and reuse it throughout the app. The interceptor automatically attaches the JWT on every non-auth request.

```dart
// lib/core/network/api_client.dart

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class ApiClient {
  static const String _baseUrl = 'http://10.0.2.2:8080/api/v1'; // change for prod
  static const _storage = FlutterSecureStorage();
  static const _tokenKey = 'jwt_token';

  late final Dio _dio;

  ApiClient() {
    _dio = Dio(BaseOptions(
      baseUrl: _baseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
      headers: {'Content-Type': 'application/json'},
    ));

    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
        // Skip token injection for auth endpoints
        if (!options.path.contains('/auth/')) {
          final token = await _storage.read(key: _tokenKey);
          if (token != null) {
            options.headers['Authorization'] = 'Bearer $token';
          }
        }
        handler.next(options);
      },
      onError: (DioException e, handler) {
        // 401 → navigate to login (implement via a global key or event bus)
        if (e.response?.statusCode == 401) {
          // AuthEventBus.instance.add(AuthEvent.unauthenticated);
        }
        handler.next(e);
      },
    ));
  }

  Dio get dio => _dio;

  // Save token after login/register
  Future<void> saveToken(String token) =>
      _storage.write(key: _tokenKey, value: token);

  // Delete token on logout
  Future<void> clearToken() => _storage.delete(key: _tokenKey);

  // Read raw token (for STOMP connect header)
  Future<String?> readToken() => _storage.read(key: _tokenKey);
}

// Singleton
final apiClient = ApiClient();
```

---

## 3. Authentication & Token Management

### Token lifecycle

| Step | Action |
|---|---|
| Register / Login | Save `AuthResponse.token` to `FlutterSecureStorage` |
| Every request | Interceptor reads and injects `Authorization: Bearer <token>` |
| Logout | Call `POST /auth/logout` then clear token from storage |
| Token expiry | Backend returns `401` — redirect to login screen |

```dart
// lib/core/auth/auth_service.dart

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class AuthService {
  static const _storage = FlutterSecureStorage();
  static const _tokenKey   = 'jwt_token';
  static const _userIdKey  = 'user_id';
  static const _emailKey   = 'user_email';
  static const _nameKey    = 'user_name';

  Future<void> saveSession(AuthResponse resp) async {
    await _storage.write(key: _tokenKey,  value: resp.token);
    await _storage.write(key: _userIdKey, value: resp.user.id.toString());
    await _storage.write(key: _emailKey,  value: resp.user.email);
    await _storage.write(key: _nameKey,   value: resp.user.fullName);
  }

  Future<bool> isLoggedIn() async {
    final token = await _storage.read(key: _tokenKey);
    return token != null;
  }

  Future<int?> getUserId() async {
    final id = await _storage.read(key: _userIdKey);
    return id == null ? null : int.tryParse(id);
  }

  Future<void> clearSession() async => _storage.deleteAll();
}
```

---

## 4. Error Handling

Every failed request returns a consistent JSON shape. Parse this in your repository layer.

### Error response shape

```json
{
  "timestamp": "2026-03-07T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "path": "/api/v1/auth/register",
  "fieldErrors": {
    "email": "must be a well-formed email address",
    "password": "size must be between 8 and 72"
  }
}
```

```dart
// lib/core/network/api_exception.dart

class ApiException implements Exception {
  final int statusCode;
  final String code;
  final String message;
  final Map<String, String>? fieldErrors;

  const ApiException({
    required this.statusCode,
    required this.code,
    required this.message,
    this.fieldErrors,
  });

  factory ApiException.fromDio(DioException e) {
    final data = e.response?.data;
    if (data is Map<String, dynamic>) {
      return ApiException(
        statusCode: e.response?.statusCode ?? 0,
        code:       data['code']    ?? 'UNKNOWN',
        message:    data['message'] ?? e.message ?? 'Unknown error',
        fieldErrors: (data['fieldErrors'] as Map<String, dynamic>?)
            ?.map((k, v) => MapEntry(k, v.toString())),
      );
    }
    return ApiException(
      statusCode: e.response?.statusCode ?? 0,
      code:       'NETWORK_ERROR',
      message:    e.message ?? 'Network error',
    );
  }

  @override
  String toString() => 'ApiException($statusCode, $code): $message';
}
```

### Common HTTP status codes

| Status | Meaning |
|---|---|
| `200` | Success |
| `201` | Created |
| `204` | No Content (e.g. delete bookmark) |
| `400` | Validation error — check `fieldErrors` |
| `401` | Invalid / expired JWT — redirect to login |
| `403` | Forbidden — user owns neither resource nor permission |
| `404` | Resource not found |
| `409` | Conflict (e.g. email already registered) |
| `429` | Rate limited (auth routes) — back off and retry |
| `500` | Server error |

---

## 5. Auth Endpoints

> All auth endpoints are public — **do not** attach a JWT.  
> Base path: `/api/v1/auth`

---

### 5.1 Register (single-step, OTP disabled)

> Use this when `APP_OTP_ENABLED=false` (default in production you should verify).

**`POST /api/v1/auth/register`**

**Request body**

```json
{
  "fullName": "Alice Nakato",
  "registrationNumber": "2026/BIT/123",
  "email": "alice@std.must.ac.ug",
  "phoneNumber": "+256700000001",
  "campus": "main",
  "password": "SecurePass123!",
  "confirmPassword": "SecurePass123!",
  "registeredLocation": {
    "label": "MUST Main Campus",
    "lat": -0.6089,
    "lng": 30.6570
  },
  "alternateLocation": {
    "label": "Library",
    "lat": -0.6085,
    "lng": 30.6575
  }
}
```

> `email` **must** end in `@must.ac.ug` or `@std.must.ac.ug`.  
> `password` and `confirmPassword` must match and be 8–72 characters.  
> `registeredLocation` and `alternateLocation` are optional.

**Expected response** `201`

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": 1,
    "fullName": "Alice Nakato",
    "email": "alice@std.must.ac.ug",
    "registrationNumber": "2026/BIT/123",
    "phoneNumber": "+256700000001"
  }
}
```

**Flutter**

```dart
Future<AuthResponse> register(RegisterRequest req) async {
  try {
    final resp = await apiClient.dio.post('/auth/register', data: req.toJson());
    final auth = AuthResponse.fromJson(resp.data);
    await apiClient.saveToken(auth.token);
    return auth;
  } on DioException catch (e) {
    throw ApiException.fromDio(e);
  }
}
```

---

### 5.2 Register — Step 1 (OTP flow)

**`POST /api/v1/auth/register/start`**

**Request body**

```json
{
  "fullName": "Alice Nakato",
  "registrationNumber": "2026/BIT/123",
  "email": "alice@std.must.ac.ug",
  "phoneNumber": "+256700000001",
  "campus": "main",
  "registeredLocation": {
    "label": "MUST Main Campus",
    "lat": -0.6089,
    "lng": 30.6570
  }
}
```

> **`alternateLocation` fallback**: If the user does not have a `registeredLocation`, provide `alternateLocation` instead — the server will use whichever is present for proximity-based features.

**Expected response** `200`

```json
{ "message": "OTP sent to alice@std.must.ac.ug" }
```

---

### 5.3 Register — Step 2: Verify OTP

**`POST /api/v1/auth/register/verify-otp`**

**Request body**

```json
{
  "email": "alice@std.must.ac.ug",
  "otp": "48291"
}
```

> `otp` is exactly 5 digits.

**Expected response** `200`

```json
{ "message": "OTP verified" }
```

---

### 5.4 Register — Step 3: Set Password

**`POST /api/v1/auth/register/set-password`**

**Request body**

```json
{
  "email": "alice@std.must.ac.ug",
  "password": "SecurePass123!",
  "confirmPassword": "SecurePass123!"
}
```

**Expected response** `201` — same `AuthResponse` as single-step register.

---

### 5.5 Login

**`POST /api/v1/auth/login`**

**Request body**

```json
{
  "email": "alice@std.must.ac.ug",
  "password": "SecurePass123!"
}
```

**Expected response** `200`

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": 1,
    "fullName": "Alice Nakato",
    "email": "alice@std.must.ac.ug",
    "registrationNumber": "BCS/2021/001",
    "phoneNumber": "+256712345678"
  }
}
```

**Flutter**

```dart
Future<AuthResponse> login(String email, String password) async {
  try {
    final resp = await apiClient.dio.post('/auth/login', data: {
      'email': email,
      'password': password,
    });
    final auth = AuthResponse.fromJson(resp.data);
    await apiClient.saveToken(auth.token);
    return auth;
  } on DioException catch (e) {
    throw ApiException.fromDio(e);
  }
}
```

---

### 5.6 Logout

**`POST /api/v1/auth/logout`**

> Send `Authorization: Bearer <token>` header. Token is revoked server-side via Redis.

**Expected response** `200`

```json
{ "message": "Logged out" }
```

**Flutter**

```dart
Future<void> logout() async {
  try {
    // header is injected by interceptor automatically
    await apiClient.dio.post('/auth/logout');
  } catch (_) {
    // always clear local token even if request fails
  } finally {
    await apiClient.clearToken();
  }
}
```

---

### 5.7 Forgot Password

**`POST /api/v1/auth/forgot-password`**

**Request body**

```json
{ "email": "alice@std.must.ac.ug" }
```

**Expected response** `200`

```json
{
  "message": "Reset OTP sent to your email",
  "resetToken": "some-token-value",
  "devOtp": "48291"
}
```

> **`devOtp`** is only present when `APP_EMAIL_ENABLED=false` (local/dev mode). It contains the OTP directly in the response body so you can test without a real email server. In production this field will be absent — the OTP is sent by email only.  
> Store `resetToken` in state — you will need it for the UI flow, but the actual reset uses the 5-digit OTP from email.

---

### 5.8 Reset Password

**`POST /api/v1/auth/reset-password`**

**Request body**

```json
{
  "email": "alice@std.must.ac.ug",
  "otp": "83921",
  "password": "NewSecurePass456!",
  "confirmPassword": "NewSecurePass456!"
}
```

**Expected response** `200`

```json
{ "message": "Password reset successful" }
```

---

## 6. Users Endpoints

> All endpoints require `Authorization: Bearer <token>`.

---

### 6.1 Get Own Profile

**`GET /api/v1/users/profile`**

**Expected response** `200`

```json
{
  "id": 1,
  "fullName": "Alice Nakato",
  "email": "alice@std.must.ac.ug",
  "registrationNumber": "2026/BIT/123",
  "phoneNumber": "+256700000001",
  "campus": "main",
  "registeredLocation": {
    "label": "MUST Main Campus",
    "lat": -0.6089,
    "lng": 30.6570
  },
  "alternateLocation": {
    "label": "Library",
    "lat": -0.6085,
    "lng": 30.6575
  }
}
```

**Flutter**

```dart
Future<UserProfile> getMyProfile() async {
  try {
    final resp = await apiClient.dio.get('/users/profile');
    return UserProfile.fromJson(resp.data);
  } on DioException catch (e) {
    throw ApiException.fromDio(e);
  }
}
```

---

### 6.2 Get Public Profile

**`GET /api/v1/users/{id}/public`**

**Expected response** `200`

```json
{
  "id": 2,
  "fullName": "Bob Mugisha",
  "campus": "MUST Main",
  "activeListingsCount": 5,
  "memberSince": "2025-09-01T08:00:00Z"
}
```

---

### 6.3 Update Own Profile

**`PUT /api/v1/users/profile`**

> All fields are optional. Only include what you want to change.  
> `email` and `registrationNumber` are **immutable** — the server rejects these fields with `400`.

**Request body**

```json
{
  "fullName": "Alice K. Nakato",
  "phoneNumber": "+256787654321",
  "campus": "MUST Main",
  "registeredLocation": {
    "label": "New Block C",
    "lat": -0.6080,
    "lng": 30.6580
  },
  "alternateLocation": null
}
```

**Expected response** `200` — updated `UserProfile` object (same shape as 6.1)

---

### 6.4 Update Live Location

> Called by Flutter's `WorkManager` or `Geolocator` background location callback every ~20 m of movement. Used for campus zone detection and presence.

**`PUT /api/v1/users/location`**

**Request body**

```json
{
  "lat": -0.5950,
  "lng": 30.5970,
  "event": "PERIODIC_UPDATE"
}
```

> Valid `event` values: `ENTERED_CAMPUS`, `EXITED_CAMPUS`, `PERIODIC_UPDATE` (or omit entirely).

**Expected response** `200`

```json
{ "message": "Location updated" }
```

**Flutter — background location update**

```dart
import 'package:geolocator/geolocator.dart';

Future<void> updateLocation(Position pos, {String? event}) async {
  await apiClient.dio.put('/users/location', data: {
    'lat': pos.latitude,
    'lng': pos.longitude,
    if (event != null) 'event': event,
  });
}
```

---

### 6.5 Register FCM Token

> Call this once after Firebase initialises and whenever `FirebaseMessaging.onTokenRefresh` fires.

**`PUT /api/v1/users/fcm-token`**

**Request body**

```json
{ "token": "fXb9...FCM_DEVICE_TOKEN...abcd" }
```

**Expected response** `200`

```json
{ "message": "FCM token registered" }
```

**Flutter**

```dart
Future<void> registerFcmToken() async {
  final fcmToken = await FirebaseMessaging.instance.getToken();
  if (fcmToken == null) return;
  await apiClient.dio.put('/users/fcm-token', data: {'token': fcmToken});
}
```

---

## 7. Listings — Owner Actions

> All endpoints require `Authorization: Bearer <token>`.  
> Ownership is enforced server-side — you can only edit/delete **your own** listings.

---

### 7.1 Create Listing

**`POST /api/v1/listings`**

**Request body**

```json
{
  "title": "HP EliteBook 840 G6",
  "priceUgx": 1800000,
  "categoryCode": "ELECTRONICS",
  "description": "Used for 1 year, in great condition. Comes with charger.",
  "locationText": "Block A Hostel, MUST",
  "lat": -0.6089,
  "lng": 30.6570,
  "campus": "main",
  "useRegisteredLocation": false
}
```

> Valid `categoryCode` values: `ELECTRONICS`, `STATIONERY`, `BAKERY`, `CLOTHING`, `FAST_FOOD`, `BEVERAGES`, `HOME`, `BEAUTY`  
> Set `"useRegisteredLocation": true` to automatically use the coordinates from the user's profile.  
> `priceUgx` accepts `price` as an alias.

**Expected response** `200`

```json
{
  "id": 101,
  "ownerUserId": 1,
  "title": "HP EliteBook 840 G6",
  "priceUgx": 1800000,
  "currency": "UGX",
  "categoryCode": "ELECTRONICS",
  "description": "Used for 1 year, in great condition. Comes with charger.",
  "locationText": "Block A Hostel, MUST",
  "campus": "MUST Main",
  "status": "ACTIVE",
  "actions": {
    "canEdit": true,
    "canMarkSold": true,
    "canDelete": true,
    "canRestore": false,
    "canPurge": false
  },
  "createdAt": "2026-03-07T10:00:00Z",
  "primaryImageUrl": null,
  "images": []
}
```

---

### 7.2 My Listings

**`GET /api/v1/listings/my`**

**Query params** (all optional)

| Param | Type | Example |
|---|---|---|
| `status` | String | `ALL`, `ACTIVE`, `SOLD`, `DELETED`, `PENDING` |

**Expected response** `200`

```json
{
  "items": [
    {
      "id": 101,
      "ownerUserId": 1,
      "title": "HP EliteBook 840 G6",
      "priceUgx": 1800000,
      "currency": "UGX",
      "categoryCode": "ELECTRONICS",
      "status": "ACTIVE",
      "actions": { "canEdit": true, "canMarkSold": true, "canDelete": true, "canRestore": false, "canPurge": false },
      "createdAt": "2026-03-07T10:00:00Z",
      "primaryImageUrl": "https://res.cloudinary.com/...",
      "images": []
    }
  ]
}
```

---

### 7.3 Update Listing

**`PUT /api/v1/listings/{id}`**

> Only include fields you want to change.

**Request body**

```json
{
  "title": "HP EliteBook 840 G6 — Price Reduced!",
  "priceUgx": 1600000,
  "description": "Selling fast — great deal, barely used."
}
```

**Expected response** `200` — updated `ListingResponse`

---

### 7.4 Mark Listing as Sold

**`POST /api/v1/listings/{id}/sold`**

No request body.

**Expected response** `200`

```json
{
  "id": 101,
  "status": "SOLD",
  "actions": { "canEdit": false, "canMarkSold": false, "canDelete": false, "canRestore": false, "canPurge": false }
}
```

---

### 7.5 Delete Listing (Soft)

**`POST /api/v1/listings/{id}/delete`**

No request body.

**Expected response** `200`

```json
{
  "id": 101,
  "status": "DELETED",
  "actions": { "canEdit": false, "canMarkSold": false, "canDelete": false, "canRestore": true, "canPurge": true }
}
```

---

### 7.6 Restore Deleted Listing

**`POST /api/v1/listings/{id}/restore`**

No request body.

**Expected response** `200`

```json
{
  "id": 101,
  "status": "ACTIVE",
  "actions": { "canEdit": true, "canMarkSold": true, "canDelete": true, "canRestore": false, "canPurge": false }
}
```

---

### 7.7 Hard Delete (Purge)

**`POST /api/v1/listings/{id}/purge`**

> Permanently removes listing and all associated images from the database. Use only if the user explicitly confirms.

No request body.

**Expected response** `200`

```json
{ "message": "Purged" }
```

---

### 7.8 Attach Image to Listing

> After uploading directly to Cloudinary (see [Section 13](#13-image-uploads-cloudinary)), call this to link the image to the listing.

**`POST /api/v1/listings/{id}/images`**

**Request body**

```json
{
  "publicId": "campusplug/listings/101/cover",
  "secureUrl": "https://res.cloudinary.com/your-cloud/image/upload/v172.../campusplug/listings/101/cover.jpg",
  "width": 1200,
  "height": 900,
  "bytes": 245760,
  "format": "jpg"
}
```

**Expected response** `200` — updated `ListingResponse` with `images` array populated

---

### 7.9 Remove Image from Listing

**`DELETE /api/v1/listings/{id}/images/{imageId}`**

**Expected response** `200` — updated `ListingResponse`

---

## 8. Listings — Browse / Public

> Auth required (JWT needed), but no ownership check.

---

### 8.1 Full-Text Search

**`GET /api/v1/listings/search`**

**Query params**

| Param | Required | Type | Example |
|---|---|---|---|
| `query` | **Yes** | String | `laptop` |
| `categoryCode` | No | String | `ELECTRONICS` |
| `campus` | No | String | `MUST Main` |
| `minPriceUgx` | No | Long | `500000` |
| `maxPriceUgx` | No | Long | `2000000` |
| `page` | No | int | `0` |
| `size` | No | int | `20` |

**Expected response** `200`

```json
{
  "items": [
    {
      "id": 101,
      "title": "HP EliteBook 840 G6",
      "priceUgx": 1800000,
      "currency": "UGX",
      "categoryCode": "ELECTRONICS",
      "locationText": "Block A Hostel, MUST",
      "campus": "MUST Main",
      "primaryImageUrl": "https://res.cloudinary.com/...",
      "createdAt": "2026-03-07T10:00:00Z",
      "distanceMeters": null,
      "ownerFullName": "Alice Nakato"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

**Flutter**

```dart
Future<ListingPage> searchListings({
  required String query,
  String? categoryCode,
  String? campus,
  int? minPriceUgx,
  int? maxPriceUgx,
  int page = 0,
  int size = 20,
}) async {
  final resp = await apiClient.dio.get('/listings/search', queryParameters: {
    'query': query,
    if (categoryCode != null) 'categoryCode': categoryCode,
    if (campus != null) 'campus': campus,
    if (minPriceUgx != null) 'minPriceUgx': minPriceUgx,
    if (maxPriceUgx != null) 'maxPriceUgx': maxPriceUgx,
    'page': page,
    'size': size,
  });
  return ListingPage.fromJson(resp.data);
}
```

---

### 8.2 Nearby Listings

**`GET /api/v1/listings/nearby`**

**Query params**

| Param | Required | Type | Example |
|---|---|---|---|
| `lat` | **Yes** | double | `-0.5950` |
| `lng` | **Yes** | double | `30.5970` |
| `radiusKm` | **Yes** | double | `5.0` |
| `categoryCode` | No | String | `FOOD` |
| `campus` | No | String | — |
| `page` | No | int | `0` |
| `size` | No | int | `20` |

**Expected response** `200` — `ListingPageResponse` (same shape as 8.1, `distanceMeters` populated)

---

### 8.3 Feed (All Listings Sorted by Distance)

**`GET /api/v1/listings/feed`**

**Query params**

| Param | Required | Type |
|---|---|---|
| `lat` | **Yes** | double |
| `lng` | **Yes** | double |
| `page` | No | int (default 0) |
| `size` | No | int (default 20) |

**Expected response** `200` — `ListingPageResponse`

---

### 8.4 Listings in a Campus Zone

**`GET /api/v1/listings/zone/{tag}`**

| Param | Location | Type | Example |
|---|---|---|---|
| `tag` | Path | String | `kihumuro_main` |
| `lat` | Query | double | `-0.5950` |
| `lng` | Query | double | `30.5970` |
| `page` | Query | int | `0` |
| `size` | Query | int | `20` |

**Expected response** `200` — `ListingPageResponse` sorted by proximity

---

### 8.5 Count Active Listings in Zone

**`GET /api/v1/listings/zone/{tag}/count`**

**Expected response** `200`

```json
{
  "zoneTag": "kihumuro_main",
  "count": 42
}
```

---

## 9. Categories Endpoints

### 9.1 List All Categories

**`GET /api/v1/categories`**

**Expected response** `200`

```json
[
  {
    "code": "ELECTRONICS",
    "displayName": "Electronics",
    "coverImageUrl": "https://res.cloudinary.com/.../electronics_cover.jpg",
    "activeListingCount": 34,
    "badge": null
  },
  {
    "code": "FAST_FOOD",
    "displayName": "Fast Food",
    "coverImageUrl": "https://res.cloudinary.com/.../fastfood_cover.jpg",
    "activeListingCount": 12,
    "badge": "HOT"
  }
]
```

> Full list of `code` values: `ELECTRONICS`, `STATIONERY`, `BAKERY`, `CLOTHING`, `FAST_FOOD`, `BEVERAGES`, `HOME`, `BEAUTY`

---

## 10. Bookmarks Endpoints

> All endpoints require `Authorization: Bearer <token>`.

---

### 10.1 Add Bookmark

**`POST /api/v1/bookmarks`**

**Request body**

```json
{ "listingId": 101 }
```

**Expected response** `201`

```json
{
  "id": 101,
  "title": "HP EliteBook 840 G6",
  "priceUgx": 1800000,
  "currency": "UGX",
  "categoryCode": "ELECTRONICS",
  "locationText": "Block A Hostel, MUST",
  "campus": "MUST Main",
  "primaryImageUrl": "https://res.cloudinary.com/...",
  "createdAt": "2026-03-07T10:00:00Z",
  "status": "ACTIVE",
  "bookmarkedAt": "2026-03-07T14:30:00Z",
  "distanceMeters": null
}
```

---

### 10.2 List Bookmarks

**`GET /api/v1/bookmarks`**

**Query params** (all optional)

| Param | Type | Notes |
|---|---|---|
| `lat` | double | If provided with `lng`, `distanceMeters` is populated |
| `lng` | double | — |
| `page` | int (default 0) | — |
| `size` | int (default 20) | — |

**Expected response** `200`

```json
{
  "items": [ /* BookmarkCardResponse */ ],
  "page": 0,
  "size": 20,
  "total": 5
}
```

---

### 10.3 Remove Bookmark

**`DELETE /api/v1/bookmarks?listingId={id}`**

**Expected response** `204 No Content`

**Flutter**

```dart
Future<void> removeBookmark(int listingId) async {
  await apiClient.dio.delete('/bookmarks', queryParameters: {'listingId': listingId});
}
```

---

## 11. Conversations Endpoints

> All endpoints require `Authorization: Bearer <token>`.

---

### 11.1 Create or Get Conversation

> Sending a message about a listing starts a conversation. If a conversation for this (user, listing) pair already exists, the existing one is returned.

**`POST /api/v1/conversations`**

**Request body**

```json
{ "listingId": 101 }
```

**Expected response** `200` or `201`

```json
{
  "id": 5,
  "listingId": 101,
  "listingTitle": "HP EliteBook 840 G6",
  "inquirerUserId": 2,
  "posterUserId": 1,
  "createdAt": "2026-03-07T15:00:00Z",
  "updatedAt": "2026-03-07T15:00:00Z"
}
```

---

### 11.2 List My Conversations

**`GET /api/v1/conversations`**

**Query params** (optional)

| Param | Default |
|---|---|
| `page` | `0` |
| `size` | `20` |

**Expected response** `200`

```json
{
  "items": [
    {
      "id": 5,
      "listingId": 101,
      "listingTitle": "HP EliteBook 840 G6",
      "counterpartUserId": 1,
      "counterpartFullName": "Alice Nakato",
      "counterpartEmail": "alice@std.must.ac.ug",
      "counterpartPhoneNumber": "+256712345678",
      "counterpartLocationText": "Block A Hostel",
      "lastMessageBody": "Is it still available?",
      "lastMessageAt": "2026-03-07T15:05:00Z",
      "counterpartActiveNow": true
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

> `counterpartActiveNow` is `true` if the other user connected to WebSocket in the last 60 seconds.

---

## 12. Messages Endpoints

> All endpoints require `Authorization: Bearer <token>`.

---

### 12.1 Send Message

**`POST /api/v1/conversations/{conversationId}/messages`**

**Request body**

```json
{ "body": "Is it still available?" }
```

> Maximum message length: 2000 characters.

**Expected response** `201`

```json
{
  "id": 50,
  "conversationId": 5,
  "senderUserId": 2,
  "body": "Is it still available?",
  "createdAt": "2026-03-07T15:05:00Z"
}
```

> The backend also broadcasts this to `/topic/conversations.5` via STOMP.

---

### 12.2 Get Latest Messages

**`GET /api/v1/conversations/{conversationId}/messages`**

**Query params**

| Param | Default | Notes |
|---|---|---|
| `limit` | `50` | Max messages to return |

**Expected response** `200`

```json
{
  "items": [
    {
      "id": 50,
      "conversationId": 5,
      "senderUserId": 2,
      "body": "Is it still available?",
      "createdAt": "2026-03-07T15:05:00Z"
    }
  ]
}
```

---

### 12.3 Long-Poll for New Messages

> Use this as a fallback if WebSocket is unavailable (e.g. background state). The server holds the connection open for up to `timeoutSeconds` and returns as soon as a new message arrives.

**`GET /api/v1/conversations/{conversationId}/messages/long-poll`**

**Query params**

| Param | Required | Default |
|---|---|---|
| `afterMessageId` | **Yes** | — (last known message ID) |
| `timeoutSeconds` | No | `25` |

**Expected response** `200`

```json
{
  "items": [
    {
      "id": 51,
      "conversationId": 5,
      "senderUserId": 1,
      "body": "Yes, still available!",
      "createdAt": "2026-03-07T15:06:00Z"
    }
  ]
}
```

> Returns `{ "items": [] }` on timeout (no new messages).

---

## 13. Image Uploads (Cloudinary)

> Uploads go **directly to Cloudinary** from the app — never through the backend server.  
> The backend only provides a **signed upload signature** and stores the resulting `publicId`/`secureUrl`.

### Full upload flow

```
1. App picks image (image_picker)
2. App calls POST /api/v1/uploads/cloudinary/signature  → gets { signature, apiKey, timestamp, cloudName, params }
3. App uploads image multipart directly to Cloudinary
4. Cloudinary returns { public_id, secure_url, width, height, bytes, format }
5. App calls POST /api/v1/listings/{id}/images with the Cloudinary result
```

---

### 13.1 Get Upload Signature

**`POST /api/v1/uploads/cloudinary/signature`**

**Request body**

```json
{
  "listingId": 101,
  "folder": "campusplug/listings/101",
  "publicId": "101-main",
  "overwrite": false
}
```

> `timestamp` is optional — the server uses the current Unix time if omitted.

**Expected response** `200`

```json
{
  "cloudName": "your-cloud-name",
  "apiKey": "123456789012345",
  "timestamp": 1709812800,
  "signature": "a9f3e...",
  "params": {
    "folder": "campusplug/listings/101",
    "public_id": "campusplug/listings/101/main_image",
    "overwrite": "true"
  }
}
```

---

### 13.2 Upload to Cloudinary

```dart
import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';

Future<Map<String, dynamic>> uploadToCloudinary({
  required XFile imageFile,
  required CloudinarySignatureResponse sig,
}) async {
  final formData = FormData.fromMap({
    'file':       await MultipartFile.fromFile(imageFile.path, filename: imageFile.name),
    'api_key':    sig.apiKey,
    'timestamp':  sig.timestamp.toString(),
    'signature':  sig.signature,
    ...sig.params, // folder, public_id, overwrite, etc.
  });

  final cloudinaryUrl =
      'https://api.cloudinary.com/v1_1/${sig.cloudName}/image/upload';

  final resp = await Dio().post(cloudinaryUrl, data: formData);
  return resp.data as Map<String, dynamic>;
}
```

### 13.3 Attach to Listing

```dart
Future<ListingResponse> attachImage(int listingId, Map<String, dynamic> cloudinaryResult) async {
  final resp = await apiClient.dio.post('/listings/$listingId/images', data: {
    'publicId':  cloudinaryResult['public_id'],
    'secureUrl': cloudinaryResult['secure_url'],
    'width':     cloudinaryResult['width'],
    'height':    cloudinaryResult['height'],
    'bytes':     cloudinaryResult['bytes'],
    'format':    cloudinaryResult['format'],
  });
  return ListingResponse.fromJson(resp.data);
}
```

---

## 14. Geo / Geocoding Endpoints

> Backed by Google Maps Geocoding API.

### 14.1 Forward Geocode (Address → Coordinates)

**`GET /api/v1/geo/geocode?address=MUST%20Mbarara%2C%20Uganda`**

**Expected response** `200`

```json
{
  "lat": -0.6089,
  "lng": 30.6570,
  "address": "Mbarara University of Science and Technology, Mbarara, Uganda"
}
```

---

### 14.2 Reverse Geocode (Coordinates → Address)

**`GET /api/v1/geo/reverse?lat=-0.6089&lng=30.6570`**

**Expected response** `200`

```json
{
  "lat": -0.6089,
  "lng": 30.6570,
  "address": "Mbarara University of Science and Technology, Mbarara, Uganda"
}
```

---

## 15. Zones Endpoints

### 15.1 List All Campus Zones

**`GET /api/v1/zones`**

**Expected response** `200`

```json
[
  {
    "id": 1,
    "name": "Kihumuro Main",
    "tag": "kihumuro_main",
    "accessType": "full"
  },
  {
    "id": 2,
    "name": "Kihumuro Buffer",
    "tag": "kihumuro_buffer",
    "accessType": "buffer"
  }
]
```

> `accessType` values: `"full"` (full marketplace access), `"buffer"` (limited), `"restricted"` (no access)  
> Zone boundary polygons are **not** returned — use hardcoded KMZ coordinates in the Flutter app for rendering zone overlays on the map.

---

## 16. Location Check Endpoint

> Call this whenever the user moves more than ~20 metres. Determines which campus zone the user is currently in, which drives UI state (e.g. showing/hiding campus-only listings).

**`POST /api/v1/location/check`**

**Request body**

```json
{
  "lat": -0.5950,
  "lng": 30.5970
}
```

**Expected response** `200`

```json
{
  "zoneName": "Kihumuro Main",
  "zoneTag": "kihumuro_main",
  "accessType": "full",
  "listingCount": 42,
  "previousZoneTag": null
}
```

> Use `listingCount` to build local notification text like _"42 listings near you on MUST Main Campus"_.  
> `previousZoneTag` is non-null when the user has just transitioned zones.

**Flutter — Geolocator integration**

```dart
import 'package:geolocator/geolocator.dart';

double _lastLat = 0, _lastLng = 0;
const double _minMovementMeters = 20;

void startLocationTracking() {
  Geolocator.getPositionStream(
    locationSettings: const LocationSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 20, // metres between updates
    ),
  ).listen((Position pos) async {
    await checkZone(pos.latitude, pos.longitude);
    await updateLocation(pos); // also update server
  });
}

Future<LocationCheckResponse> checkZone(double lat, double lng) async {
  final resp = await apiClient.dio.post('/location/check', data: {
    'lat': lat,
    'lng': lng,
  });
  return LocationCheckResponse.fromJson(resp.data);
}
```

---

## 17. WebSocket / Real-time Messaging

> The backend exposes a **STOMP-over-WebSocket** endpoint.  
> JWT is passed as a native STOMP header on `CONNECT`, not as a URL query parameter.

### Connection details

| Setting | Value |
|---|---|
| WebSocket URL | `ws://your-host/ws` |
| STOMP destination prefix | `/app` |
| Topic prefix (subscribe) | `/topic` |
| Auth header on CONNECT | `Authorization: Bearer <token>` |

### Topics

| Topic | Event | Payload |
|---|---|---|
| `/topic/conversations.{conversationId}` | New message in conversation | `MessageResponse` |
| `/topic/listings.new` | New listing created | `ListingNewEvent` |

`ListingNewEvent` fields: `id`, `title`, `priceUgx`, `currency`, `categoryCode`, `campus`, `createdAt`

---

### Flutter STOMP Integration

```dart
// lib/core/realtime/stomp_service.dart

import 'package:stomp_dart_client/stomp_dart_client.dart';

class StompService {
  StompClient? _client;

  Future<void> connect({
    required String token,
    required String host, // e.g. "10.0.2.2:8080"
  }) async {
    _client = StompClient(
      config: StompConfig.sockJS(
        url: 'http://$host/ws',
        onConnect: _onConnect,
        onDisconnect: (_) => print('STOMP disconnected'),
        onWebSocketError: (e) => print('WebSocket error: $e'),
        // JWT in connect headers
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
        reconnectDelay: const Duration(seconds: 5),
      ),
    );
    _client!.activate();
  }

  void _onConnect(StompFrame frame) {
    print('STOMP connected: ${frame.headers}');
  }

  /// Subscribe to messages for a specific conversation
  StompUnsubscribe subscribeToConversation({
    required int conversationId,
    required void Function(MessageResponse msg) onMessage,
  }) {
    return _client!.subscribe(
      destination: '/topic/conversations.$conversationId',
      callback: (frame) {
        if (frame.body != null) {
          final msg = MessageResponse.fromJson(jsonDecode(frame.body!));
          onMessage(msg);
        }
      },
    );
  }

  /// Subscribe to new listing events globally
  StompUnsubscribe subscribeToNewListings({
    required void Function(Map<String, dynamic> event) onListing,
  }) {
    return _client!.subscribe(
      destination: '/topic/listings.new',
      callback: (frame) {
        if (frame.body != null) {
          onListing(jsonDecode(frame.body!));
        }
      },
    );
  }

  void disconnect() => _client?.deactivate();
}
```

### Typical chat screen usage

```dart
class _ChatScreenState extends State<ChatScreen> {
  late StompUnsubscribe _unsub;
  List<MessageResponse> _messages = [];

  @override
  void initState() {
    super.initState();
    _loadHistory();
    _unsub = stompService.subscribeToConversation(
      conversationId: widget.conversationId,
      onMessage: (msg) => setState(() => _messages.add(msg)),
    );
  }

  @override
  void dispose() {
    _unsub();
    super.dispose();
  }

  Future<void> _loadHistory() async {
    final resp = await apiClient.dio.get(
      '/conversations/${widget.conversationId}/messages',
      queryParameters: {'limit': 50},
    );
    setState(() {
      _messages = (resp.data['items'] as List)
          .map((e) => MessageResponse.fromJson(e))
          .toList();
    });
  }

  Future<void> _sendMessage(String text) async {
    await apiClient.dio.post(
      '/conversations/${widget.conversationId}/messages',
      data: {'body': text},
    );
    // The STOMP subscription delivers the message back to us automatically.
  }
}
```

### Important notes on STOMP

- Call `connect()` right after a successful login.
- Call `disconnect()` on logout.
- On app resume from background, check if connected and reconnect if needed (`StompConfig.reconnectDelay` handles this automatically).
- For background message delivery use the **long-poll** endpoint (Section 12.3) or FCM (Section 18).
- The presence system marks you **online** (60s TTL) when you are STOMP-connected. The `counterpartActiveNow` flag in conversations reflects this.

---

## 18. FCM Push Notifications

> The backend sends FCM push notifications for new messages and nearby listing alerts.

### Setup steps

1. Add `google-services.json` (Android) and `GoogleService-Info.plist` (iOS) to your app.
2. Initialise Firebase in `main.dart`.
3. Request notification permissions.
4. Register the FCM token with the backend after login (see Section 6.5).

```dart
// lib/main.dart

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';

@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
  // handle background notification
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);
  runApp(const CampusPlugApp());
}
```

```dart
// lib/core/notifications/notification_service.dart

class NotificationService {
  Future<void> init() async {
    // Request permission
    await FirebaseMessaging.instance.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );

    // Register token with backend
    final token = await FirebaseMessaging.instance.getToken();
    if (token != null) {
      await apiClient.dio.put('/users/fcm-token', data: {'token': token});
    }

    // Listen for token refresh
    FirebaseMessaging.instance.onTokenRefresh.listen((newToken) async {
      await apiClient.dio.put('/users/fcm-token', data: {'token': newToken});
    });

    // Handle foreground messages
    FirebaseMessaging.onMessage.listen((RemoteMessage message) {
      // Show local notification using flutter_local_notifications
      _showLocalNotification(message);
    });
  }

  void _showLocalNotification(RemoteMessage message) {
    // Implement with flutter_local_notifications
    print('FCM foreground: ${message.notification?.title}');
  }
}
```

---

## 19. Environment & Configuration

### Base URL pattern

```dart
// lib/core/config/app_config.dart

class AppConfig {
  static const bool isProduction = bool.fromEnvironment('PRODUCTION', defaultValue: false);

  static const String _prodBaseUrl  = 'https://campusplug-api.onrender.com/api/v1';
  static const String _localBaseUrl = 'http://10.0.2.2:8080/api/v1'; // Android emulator

  static String get baseUrl => isProduction ? _prodBaseUrl : _localBaseUrl;
}
```

> For a physical device on the same Wi-Fi as your dev machine, replace `10.0.2.2` with your machine's local IP address.

### Allowed email domains

Only `@must.ac.ug` and `@std.must.ac.ug` addresses can register. Validate this client-side to provide instant feedback:

```dart
bool isAllowedEmail(String email) {
  return email.endsWith('@must.ac.ug') || email.endsWith('@std.must.ac.ug');
}
```

### CORS

The backend allows `http://localhost:3000` and `http://localhost:5173` by default. Mobile apps communicate via HTTP/HTTPS so CORS does not apply to Flutter.

---

## 20. Complete Dart Model Classes

Reference implementations for all request/response types. Use `json_serializable` or copy-paste and implement `fromJson`/`toJson` manually.

```dart
// ─────────────────────────────────────────────────────
// AUTH
// ─────────────────────────────────────────────────────

class AuthUser {
  final int id;
  final String fullName;
  final String email;
  final String registrationNumber;
  final String? phoneNumber;

  const AuthUser({required this.id, required this.fullName, required this.email,
    required this.registrationNumber, this.phoneNumber});

  factory AuthUser.fromJson(Map<String, dynamic> j) => AuthUser(
    id: j['id'], fullName: j['fullName'], email: j['email'],
    registrationNumber: j['registrationNumber'], phoneNumber: j['phoneNumber'],
  );
}

class AuthResponse {
  final String token;
  final AuthUser user;
  const AuthResponse({required this.token, required this.user});
  factory AuthResponse.fromJson(Map<String, dynamic> j) =>
      AuthResponse(token: j['token'], user: AuthUser.fromJson(j['user']));
}

class RegisteredLocationDto {
  final String? label;
  final double? lat;
  final double? lng;
  const RegisteredLocationDto({this.label, this.lat, this.lng});
  Map<String, dynamic> toJson() => {'label': label, 'lat': lat, 'lng': lng};
  factory RegisteredLocationDto.fromJson(Map<String, dynamic> j) =>
      RegisteredLocationDto(label: j['label'], lat: j['lat']?.toDouble(), lng: j['lng']?.toDouble());
}

// ─────────────────────────────────────────────────────
// USERS
// ─────────────────────────────────────────────────────

class UserProfile {
  final int id;
  final String fullName;
  final String email;
  final String registrationNumber;
  final String? phoneNumber;
  final String? campus;
  final RegisteredLocationDto? registeredLocation;
  final RegisteredLocationDto? alternateLocation;

  const UserProfile({required this.id, required this.fullName, required this.email,
    required this.registrationNumber, this.phoneNumber, this.campus,
    this.registeredLocation, this.alternateLocation});

  factory UserProfile.fromJson(Map<String, dynamic> j) => UserProfile(
    id: j['id'], fullName: j['fullName'], email: j['email'],
    registrationNumber: j['registrationNumber'], phoneNumber: j['phoneNumber'],
    campus: j['campus'],
    registeredLocation: j['registeredLocation'] != null
        ? RegisteredLocationDto.fromJson(j['registeredLocation']) : null,
    alternateLocation: j['alternateLocation'] != null
        ? RegisteredLocationDto.fromJson(j['alternateLocation']) : null,
  );
}

class PublicUserProfile {
  final int id;
  final String fullName;
  final String? campus;
  final int activeListingsCount;
  final DateTime memberSince;

  const PublicUserProfile({required this.id, required this.fullName, this.campus,
    required this.activeListingsCount, required this.memberSince});

  factory PublicUserProfile.fromJson(Map<String, dynamic> j) => PublicUserProfile(
    id: j['id'], fullName: j['fullName'], campus: j['campus'],
    activeListingsCount: j['activeListingsCount'],
    memberSince: DateTime.parse(j['memberSince']),
  );
}

// ─────────────────────────────────────────────────────
// LISTINGS
// ─────────────────────────────────────────────────────

enum ListingStatus { PENDING, ACTIVE, SOLD, DELETED }

class ListingActions {
  final bool canEdit, canMarkSold, canDelete, canRestore, canPurge;
  const ListingActions({required this.canEdit, required this.canMarkSold,
    required this.canDelete, required this.canRestore, required this.canPurge});
  factory ListingActions.fromJson(Map<String, dynamic> j) => ListingActions(
    canEdit: j['canEdit'] ?? false, canMarkSold: j['canMarkSold'] ?? false,
    canDelete: j['canDelete'] ?? false, canRestore: j['canRestore'] ?? false,
    canPurge: j['canPurge'] ?? false,
  );
}

class ListingImageResponse {
  final int id;
  final String publicId;
  final String secureUrl;
  final int? width, height;
  final int? bytes;
  final String? format;
  final DateTime createdAt;

  const ListingImageResponse({required this.id, required this.publicId,
    required this.secureUrl, this.width, this.height, this.bytes, this.format,
    required this.createdAt});

  factory ListingImageResponse.fromJson(Map<String, dynamic> j) =>
      ListingImageResponse(
        id: j['id'], publicId: j['publicId'], secureUrl: j['secureUrl'],
        width: j['width'], height: j['height'], bytes: j['bytes'],
        format: j['format'], createdAt: DateTime.parse(j['createdAt']),
      );
}

class ListingResponse {
  final int id;
  final int ownerUserId;
  final String title;
  final int priceUgx;
  final String currency;
  final String categoryCode;
  final String? description;
  final String? locationText;
  final String? campus;
  final ListingStatus status;
  final ListingActions actions;
  final DateTime createdAt;
  final String? primaryImageUrl;
  final List<ListingImageResponse> images;

  const ListingResponse({required this.id, required this.ownerUserId,
    required this.title, required this.priceUgx, required this.currency,
    required this.categoryCode, this.description, this.locationText, this.campus,
    required this.status, required this.actions, required this.createdAt,
    this.primaryImageUrl, required this.images});

  factory ListingResponse.fromJson(Map<String, dynamic> j) => ListingResponse(
    id: j['id'], ownerUserId: j['ownerUserId'], title: j['title'],
    priceUgx: j['priceUgx'], currency: j['currency'],
    categoryCode: j['categoryCode'], description: j['description'],
    locationText: j['locationText'], campus: j['campus'],
    status: ListingStatus.values.byName(j['status']),
    actions: ListingActions.fromJson(j['actions']),
    createdAt: DateTime.parse(j['createdAt']),
    primaryImageUrl: j['primaryImageUrl'],
    images: (j['images'] as List? ?? [])
        .map((e) => ListingImageResponse.fromJson(e)).toList(),
  );
}

class ListingCardResponse {
  final int id;
  final String title;
  final int priceUgx;
  final String currency;
  final String categoryCode;
  final String? locationText;
  final String? campus;
  final String? primaryImageUrl;
  final DateTime createdAt;
  final double? distanceMeters;
  final String? ownerFullName;

  const ListingCardResponse({required this.id, required this.title,
    required this.priceUgx, required this.currency, required this.categoryCode,
    this.locationText, this.campus, this.primaryImageUrl, required this.createdAt,
    this.distanceMeters, this.ownerFullName});

  factory ListingCardResponse.fromJson(Map<String, dynamic> j) =>
      ListingCardResponse(
        id: j['id'], title: j['title'], priceUgx: j['priceUgx'],
        currency: j['currency'], categoryCode: j['categoryCode'],
        locationText: j['locationText'], campus: j['campus'],
        primaryImageUrl: j['primaryImageUrl'],
        createdAt: DateTime.parse(j['createdAt']),
        distanceMeters: j['distanceMeters']?.toDouble(),
        ownerFullName: j['ownerFullName'],
      );
}

class ListingPage {
  final List<ListingCardResponse> items;
  final int page, size;
  final int total;

  const ListingPage({required this.items, required this.page,
    required this.size, required this.total});

  factory ListingPage.fromJson(Map<String, dynamic> j) => ListingPage(
    items: (j['items'] as List).map((e) => ListingCardResponse.fromJson(e)).toList(),
    page: j['page'], size: j['size'], total: j['total'],
  );
}

// ─────────────────────────────────────────────────────
// CATEGORIES
// ─────────────────────────────────────────────────────

class CategoryResponse {
  final String code;
  final String displayName;
  final String? coverImageUrl;
  final int activeListingCount;
  final String? badge;

  const CategoryResponse({required this.code, required this.displayName,
    this.coverImageUrl, required this.activeListingCount, this.badge});

  factory CategoryResponse.fromJson(Map<String, dynamic> j) => CategoryResponse(
    code: j['code'], displayName: j['displayName'],
    coverImageUrl: j['coverImageUrl'],
    activeListingCount: j['activeListingCount'], badge: j['badge'],
  );
}

// ─────────────────────────────────────────────────────
// BOOKMARKS
// ─────────────────────────────────────────────────────

class BookmarkCardResponse {
  final int id;
  final String title;
  final int priceUgx;
  final String currency;
  final String categoryCode;
  final String? locationText;
  final String? campus;
  final String? primaryImageUrl;
  final DateTime createdAt;
  final String status;
  final DateTime bookmarkedAt;
  final double? distanceMeters;

  const BookmarkCardResponse({required this.id, required this.title,
    required this.priceUgx, required this.currency, required this.categoryCode,
    this.locationText, this.campus, this.primaryImageUrl, required this.createdAt,
    required this.status, required this.bookmarkedAt, this.distanceMeters});

  factory BookmarkCardResponse.fromJson(Map<String, dynamic> j) =>
      BookmarkCardResponse(
        id: j['id'], title: j['title'], priceUgx: j['priceUgx'],
        currency: j['currency'], categoryCode: j['categoryCode'],
        locationText: j['locationText'], campus: j['campus'],
        primaryImageUrl: j['primaryImageUrl'],
        createdAt: DateTime.parse(j['createdAt']),
        status: j['status'], bookmarkedAt: DateTime.parse(j['bookmarkedAt']),
        distanceMeters: j['distanceMeters']?.toDouble(),
      );
}

// ─────────────────────────────────────────────────────
// CONVERSATIONS & MESSAGES
// ─────────────────────────────────────────────────────

class ConversationListItem {
  final int id;
  final int listingId;
  final String listingTitle;
  final int counterpartUserId;
  final String counterpartFullName;
  final String counterpartEmail;
  final String? counterpartPhoneNumber;
  final String? counterpartLocationText;
  final String? lastMessageBody;
  final DateTime? lastMessageAt;
  final bool counterpartActiveNow;

  const ConversationListItem({required this.id, required this.listingId,
    required this.listingTitle, required this.counterpartUserId,
    required this.counterpartFullName, required this.counterpartEmail,
    this.counterpartPhoneNumber, this.counterpartLocationText,
    this.lastMessageBody, this.lastMessageAt, required this.counterpartActiveNow});

  factory ConversationListItem.fromJson(Map<String, dynamic> j) =>
      ConversationListItem(
        id: j['id'], listingId: j['listingId'], listingTitle: j['listingTitle'],
        counterpartUserId: j['counterpartUserId'],
        counterpartFullName: j['counterpartFullName'],
        counterpartEmail: j['counterpartEmail'],
        counterpartPhoneNumber: j['counterpartPhoneNumber'],
        counterpartLocationText: j['counterpartLocationText'],
        lastMessageBody: j['lastMessageBody'],
        lastMessageAt: j['lastMessageAt'] != null
            ? DateTime.parse(j['lastMessageAt']) : null,
        counterpartActiveNow: j['counterpartActiveNow'] ?? false,
      );
}

class MessageResponse {
  final int id;
  final int conversationId;
  final int senderUserId;
  final String body;
  final DateTime createdAt;

  const MessageResponse({required this.id, required this.conversationId,
    required this.senderUserId, required this.body, required this.createdAt});

  factory MessageResponse.fromJson(Map<String, dynamic> j) => MessageResponse(
    id: j['id'], conversationId: j['conversationId'],
    senderUserId: j['senderUserId'], body: j['body'],
    createdAt: DateTime.parse(j['createdAt']),
  );
}

// ─────────────────────────────────────────────────────
// GEO & LOCATIONS
// ─────────────────────────────────────────────────────

class GeoResponse {
  final double lat;
  final double lng;
  final String address;

  const GeoResponse({required this.lat, required this.lng, required this.address});

  factory GeoResponse.fromJson(Map<String, dynamic> j) => GeoResponse(
    lat: j['lat'].toDouble(), lng: j['lng'].toDouble(), address: j['address'],
  );
}

class LocationCheckResponse {
  final String zoneName;
  final String zoneTag;
  final String accessType;
  final int listingCount;
  final String? previousZoneTag;

  const LocationCheckResponse({required this.zoneName, required this.zoneTag,
    required this.accessType, required this.listingCount, this.previousZoneTag});

  factory LocationCheckResponse.fromJson(Map<String, dynamic> j) =>
      LocationCheckResponse(
        zoneName: j['zoneName'], zoneTag: j['zoneTag'],
        accessType: j['accessType'], listingCount: j['listingCount'],
        previousZoneTag: j['previousZoneTag'],
      );
}

// ─────────────────────────────────────────────────────
// ZONES
// ─────────────────────────────────────────────────────

class ZoneResponse {
  final int id;
  final String name;
  final String tag;
  final String accessType;

  const ZoneResponse({required this.id, required this.name,
    required this.tag, required this.accessType});

  factory ZoneResponse.fromJson(Map<String, dynamic> j) => ZoneResponse(
    id: j['id'], name: j['name'], tag: j['tag'], accessType: j['accessType'],
  );
}

// ─────────────────────────────────────────────────────
// CLOUDINARY
// ─────────────────────────────────────────────────────

class CloudinarySignatureResponse {
  final String cloudName;
  final String apiKey;
  final int timestamp;
  final String signature;
  final Map<String, String> params;

  const CloudinarySignatureResponse({required this.cloudName, required this.apiKey,
    required this.timestamp, required this.signature, required this.params});

  factory CloudinarySignatureResponse.fromJson(Map<String, dynamic> j) =>
      CloudinarySignatureResponse(
        cloudName: j['cloudName'], apiKey: j['apiKey'], timestamp: j['timestamp'],
        signature: j['signature'],
        params: (j['params'] as Map<String, dynamic>?)
            ?.map((k, v) => MapEntry(k, v.toString())) ?? {},
      );
}
```

---

## Quick Reference — All Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/` | No | Health check |
| `GET` | `/actuator/health` | No | Server health probe |
| `GET` | `/actuator/info` | Yes | Build info |
| `POST` | `/api/v1/auth/register` | No | Single-step registration |
| `POST` | `/api/v1/auth/register/start` | No | OTP flow step 1 |
| `POST` | `/api/v1/auth/register/verify-otp` | No | OTP flow step 2 |
| `POST` | `/api/v1/auth/register/set-password` | No | OTP flow step 3 |
| `POST` | `/api/v1/auth/login` | No | Login |
| `POST` | `/api/v1/auth/logout` | Yes | Logout + revoke token |
| `POST` | `/api/v1/auth/forgot-password` | No | Request password reset OTP |
| `POST` | `/api/v1/auth/reset-password` | No | Complete password reset |
| `GET` | `/api/v1/users/profile` | Yes | Get own profile |
| `PUT` | `/api/v1/users/profile` | Yes | Update own profile |
| `GET` | `/api/v1/users/{id}/public` | Yes | Get public profile |
| `PUT` | `/api/v1/users/location` | Yes | Update live location |
| `PUT` | `/api/v1/users/fcm-token` | Yes | Register FCM device token |
| `GET` | `/api/v1/categories` | Yes | List all categories |
| `POST` | `/api/v1/listings` | Yes | Create listing |
| `GET` | `/api/v1/listings/my` | Yes | My listings |
| `PUT` | `/api/v1/listings/{id}` | Yes | Update listing |
| `POST` | `/api/v1/listings/{id}/images` | Yes | Attach image to listing |
| `DELETE` | `/api/v1/listings/{id}/images/{imageId}` | Yes | Remove image |
| `POST` | `/api/v1/listings/{id}/delete` | Yes | Soft delete listing |
| `POST` | `/api/v1/listings/{id}/restore` | Yes | Restore deleted listing |
| `POST` | `/api/v1/listings/{id}/sold` | Yes | Mark listing as sold |
| `POST` | `/api/v1/listings/{id}/purge` | Yes | Hard delete listing |
| `GET` | `/api/v1/listings/search` | Yes | Full-text search |
| `GET` | `/api/v1/listings/nearby` | Yes | Nearby listings |
| `GET` | `/api/v1/listings/feed` | Yes | Global feed |
| `GET` | `/api/v1/listings/zone/{tag}` | Yes | Listings in zone |
| `GET` | `/api/v1/listings/zone/{tag}/count` | Yes | Count listings in zone |
| `POST` | `/api/v1/bookmarks` | Yes | Add bookmark |
| `GET` | `/api/v1/bookmarks` | Yes | List bookmarks |
| `DELETE` | `/api/v1/bookmarks?listingId={id}` | Yes | Remove bookmark |
| `POST` | `/api/v1/conversations` | Yes | Create / get conversation |
| `GET` | `/api/v1/conversations` | Yes | List my conversations |
| `POST` | `/api/v1/conversations/{id}/messages` | Yes | Send message |
| `GET` | `/api/v1/conversations/{id}/messages` | Yes | Get latest messages |
| `GET` | `/api/v1/conversations/{id}/messages/long-poll` | Yes | Long-poll for new messages |
| `POST` | `/api/v1/uploads/cloudinary/signature` | Yes | Get Cloudinary upload signature |
| `GET` | `/api/v1/geo/geocode` | Yes | Forward geocode |
| `GET` | `/api/v1/geo/reverse` | Yes | Reverse geocode |
| `GET` | `/api/v1/zones` | Yes | List campus zones |
| `POST` | `/api/v1/location/check` | Yes | Check current zone |
| `WS` | `/ws` (STOMP) | JWT in CONNECT | Real-time messaging |

---

## 21. Full Buyer–Seller Conversation Walkthrough

> **Simulation / Guideline** — This section shows the complete end-to-end flow as a sequence of HTTP requests and responses. It is not a code template; it illustrates what data moves between the Flutter app and the backend at each stage.

### Scenario
- **Alice** (seller) lists a MacBook and waits for buyers.
- **Bob** (buyer) finds the listing, opens a conversation, and sends a message.
- Both sides communicate in real time via STOMP, with long-poll as fallback.

---

### Step 1 — Alice registers (OTP flow, step 1 of 3)

**Request** `POST /api/v1/auth/register/start`
```json
{
  "fullName": "Alice Seller",
  "registrationNumber": "2025/BIT/001",
  "email": "alice.seller@std.must.ac.ug",
  "phoneNumber": "+256700000001",
  "campus": "main",
  "registeredLocation": { "label": "MUST Main Campus", "lat": -0.6089, "lng": 30.6570 }
}
```
**Response** `200`
```json
{ "message": "OTP sent to alice.seller@std.must.ac.ug", "devOtp": "48291" }
```
> `devOtp` is only present when `APP_EMAIL_ENABLED=false`. In production the OTP arrives by email only.

---

### Step 2 — Alice verifies OTP (step 2 of 3)

**Request** `POST /api/v1/auth/register/verify-otp`
```json
{ "email": "alice.seller@std.must.ac.ug", "otp": "48291" }
```
**Response** `200`
```json
{ "message": "OTP verified" }
```

---

### Step 3 — Alice sets password and completes registration (step 3 of 3)

**Request** `POST /api/v1/auth/register/set-password`
```json
{
  "email": "alice.seller@std.must.ac.ug",
  "password": "password123",
  "confirmPassword": "password123"
}
```
**Response** `200` — Alice's JWT token is returned. Store it as `sellerToken`.
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": 1,
    "fullName": "Alice Seller",
    "email": "alice.seller@std.must.ac.ug",
    "registrationNumber": "2025/BIT/001",
    "phoneNumber": "+256700000001"
  }
}
```

---

### Step 4 — Bob registers and logs in

Same 3-step OTP flow as Steps 1–3, using Bob's credentials (`bob.buyer@std.must.ac.ug`, `2025/BIT/002`). Store the returned token as `buyerToken`.

---

### Step 5 — Alice creates a listing

**Request** `POST /api/v1/listings` *(with `Authorization: Bearer <sellerToken>`)*
```json
{
  "title": "MacBook Pro 2021",
  "priceUgx": 4500000,
  "categoryCode": "ELECTRONICS",
  "description": "Great condition, barely used"
}
```
**Response** `200` — The new listing ID is returned. Store it.
```json
{
  "id": 10,
  "ownerUserId": 1,
  "title": "MacBook Pro 2021",
  "priceUgx": 4500000,
  "currency": "UGX",
  "categoryCode": "ELECTRONICS",
  "status": "ACTIVE",
  "actions": { "canEdit": true, "canMarkSold": true, "canDelete": true, "canRestore": false, "canPurge": false },
  "createdAt": "2026-03-07T10:00:00Z",
  "primaryImageUrl": null,
  "images": []
}
```

---

### Step 6 — Both connect to WebSocket

Alice and Bob each establish a STOMP connection using their respective tokens (see [Section 17](#17-websocket--real-time-messaging) for connection details). Once connected, they subscribe to:

```
/topic/conversations.{conversationId}
```

> `conversationId` is obtained in Step 7. Subscribe after the conversation is created.

---

### Step 7 — Bob opens a conversation about the listing

**Request** `POST /api/v1/conversations` *(with `Authorization: Bearer <buyerToken>`)*
```json
{ "listingId": 10 }
```
**Response** `200`
```json
{
  "id": 5,
  "listingId": 10,
  "listingTitle": "MacBook Pro 2021",
  "inquirerUserId": 2,
  "posterUserId": 1,
  "createdAt": "2026-03-07T10:05:00Z",
  "updatedAt": "2026-03-07T10:05:00Z"
}
```
> If a conversation between these two users for this listing already exists, the same conversation is returned — no duplicate is created.

---

### Step 8 — Bob sends the first message

**Request** `POST /api/v1/conversations/5/messages` *(with `Authorization: Bearer <buyerToken>`)*
```json
{ "body": "Hi, is this still available?" }
```
**Response** `201`
```json
{
  "id": 50,
  "conversationId": 5,
  "senderUserId": 2,
  "body": "Hi, is this still available?",
  "createdAt": "2026-03-07T10:06:00Z"
}
```
> The backend simultaneously broadcasts this payload to `/topic/conversations.5` via STOMP. Alice's STOMP subscription receives it in real time — no polling needed.

---

### Step 9 — Alice replies

**Request** `POST /api/v1/conversations/5/messages` *(with `Authorization: Bearer <sellerToken>`)*
```json
{ "body": "Yes, still available! When can you come?" }
```
**Response** `201`
```json
{
  "id": 51,
  "conversationId": 5,
  "senderUserId": 1,
  "body": "Yes, still available! When can you come?",
  "createdAt": "2026-03-07T10:07:00Z"
}
```
> Bob's STOMP subscription fires immediately with this payload.

---

### Step 10 — Either side loads full message history

**Request** `GET /api/v1/conversations/5/messages?limit=50`

**Response** `200`
```json
{
  "items": [
    { "id": 50, "conversationId": 5, "senderUserId": 2, "body": "Hi, is this still available?", "createdAt": "2026-03-07T10:06:00Z" },
    { "id": 51, "conversationId": 5, "senderUserId": 1, "body": "Yes, still available! When can you come?", "createdAt": "2026-03-07T10:07:00Z" }
  ]
}
```

---

### Step 11 — Long-poll fallback (when STOMP is unavailable)

If the WebSocket connection drops (e.g. app goes to background), use long-poll to receive the next message:

**Request** `GET /api/v1/conversations/5/messages/long-poll?afterMessageId=51&timeoutSeconds=25`

**Response** `200` — returns immediately when a new message arrives, or after timeout:
```json
{
  "items": [
    { "id": 52, "conversationId": 5, "senderUserId": 2, "body": "Tomorrow afternoon works!", "createdAt": "2026-03-07T10:30:00Z" }
  ]
}
```
If no new messages arrive within `timeoutSeconds`:
```json
{ "items": [] }
```

---

### Step 12 — Presence: is the other person online?

Fetch the conversation list to check the `counterpartActiveNow` flag. This is `true` when the other user has been STOMP-connected within the last 60 seconds.

**Request** `GET /api/v1/conversations?page=0&size=20`

**Response** `200` (relevant field highlighted)
```json
{
  "items": [{
    "id": 5,
    "listingTitle": "MacBook Pro 2021",
    "counterpartFullName": "Bob Buyer",
    "lastMessageBody": "Tomorrow afternoon works!",
    "lastMessageAt": "2026-03-07T10:30:00Z",
    "counterpartActiveNow": true
  }]
}
```
> Use `counterpartActiveNow` to drive an online/offline indicator in the chat UI.

---

### Step 13 — Alice marks the listing sold after the deal

**Request** `POST /api/v1/listings/10/sold` *(with `Authorization: Bearer <sellerToken>`)*

No request body.

**Response** `200`
```json
{
  "id": 10,
  "status": "SOLD",
  "actions": { "canEdit": false, "canMarkSold": false, "canDelete": false, "canRestore": false, "canPurge": false }
}
```
> `SOLD` is a terminal state — all `actions` flags are `false` and no further state changes are possible.

---

### Listing state machine reference

Every listing state-change response includes an `actions` object. Use it to determine which buttons to show:

| Status | canEdit | canMarkSold | canDelete | canRestore | canPurge |
|---|---|---|---|---|---|
| `ACTIVE` | ✓ | ✓ | ✓ | ✗ | ✗ |
| `DELETED` | ✗ | ✗ | ✗ | ✓ | ✓ |
| `SOLD` | ✗ | ✗ | ✗ | ✗ | ✗ |

**Valid transitions:**
```
ACTIVE  ──[sold]────► SOLD     (terminal)
ACTIVE  ──[delete]──► DELETED ──[restore]──► ACTIVE
DELETED ──[purge]───► (permanently removed)
```

