# CampusPlug Backend (Spring Boot) — Implementation Plan (UI-aligned)

> Scope: mobile MVP backend API for a university marketplace (Listings, Users/Auth, Bookmarks, Search, Real-time Messaging) deployable on **Render Free** with **PostgreSQL + PostGIS**.
>
> Constraints: **$0 budget**, Render free services may sleep, must be portfolio-ready and production-quality without over-engineering.

**Local development setup**
- Java: **17** (system version)
- IDE: Spring Tool Suite (STS) or VS Code (either is acceptable; no impact on API requirements)

**Automation requirement (minimal/no manual intervention)**
- Local dev should run with **one command** (after initial dependency download):
  - start dependencies via Docker Compose
  - start the API
- Database schema must be created/updated via **Flyway migrations automatically on startup** (including PostGIS extension enablement).
- All configuration must be via **environment variables / application profiles** (no editing code to deploy).
- Render deploy must boot successfully without manual DB steps (no clicking SQL consoles).

---

## 0) Final decisions captured from chat (Source of Truth)

### User identity rules
- **University email domain restriction:** YES
  - Allowed domains: `must.ac.ug`, `std.must.ac.ug`
- `registrationNumber`: mandatory and user-entered, must match UI format:
  - Canonical: `YYYY/PROGRAM/NNN` with optional `/PS`
  - Example: `2023/BIT/216/PS` or `2023/BIT/216`
  - Accept input with or without separators; normalize to canonical form

### Location / Geo (PostGIS)
- **Decision:** Use **PostGIS** for geo features (nearby listings, distance sorting, radius filtering).
  - Mobile app will send `lat`/`lng`; backend stores them as a PostGIS point for fast spatial queries.
  - **No manual DB steps on Render**: PostGIS is enabled via Flyway migration on startup.
- User “registered location” must store: **label + lat/lng**
- Listing location supports:
  - `useRegisteredLocation=true` → copy user registered location (label + point)
  - else alternate `locationText` + **lat/lng** (recommended for consistent nearby results)
- Storage strategy (MVP):
  - Store `location_text` for UI display
  - Store `geo` as `geography(Point, 4326)` for PostGIS queries
  - (Optional) also store `lat`/`lng` as numeric fields for debugging/interop, but queries should use `geo`

### Email verification
- **Optional** in MVP (not required to post or message)

### Messaging
- **Text-only** MVP
- **Max chat message length:** **2000 characters**
- **Business rule:** if a listing is **SOLD**, backend must **block sending messages** for conversations linked to that listing (return 409/400 with clear error code)
- **Realtime:** WebSocket required
- **Fallback:** **Long HTTP polling** (server holds request until new message or timeout)
  - Endpoint pattern: `GET /api/v1/conversations/{id}/messages/long-poll?afterMessageId=...&timeoutSeconds=...`
  - Default `timeoutSeconds`: **25** (keep below common proxy timeouts)
  - Client behavior: immediately re-issue long-poll request after response while chat is open
  - Cursor: **`afterMessageId` required** (stable ordering)

### Realtime product/listing notifications (WebSocket)
Goal: push **new product/listing alerts** to mobile clients in real time.

**Channel**
- Use the existing Spring WebSocket + STOMP connection (same WS infrastructure as chat).

**Topics (MVP)**
- Global: `/topic/listings.new`
- Campus-scoped: `/topic/listings.new.{campus}` (campus normalized to lowercase)

**When to publish**
- Publish after a listing becomes `ACTIVE` (after auto-approval on create).
- Do not publish for `PENDING`, `DELETED`, or `SOLD` transitions.

**Event payload (minimum)**
- `listingId`, `title`, `price`, `currency`, `category`, `campus`, `locationText`, `primaryImageUrl`, `createdAt`

**Client behavior (Flutter)**
- Subscribe to `/topic/listings.new.{campus}` based on the user’s registered campus.
- If WS is disconnected (Render sleep), fall back to normal feed refresh (no separate long-poll needed for this feature).

### Delete/Purge behavior
- My Listings supports:
  - Soft delete → status `DELETED`
  - Restore → `DELETED -> ACTIVE`
  - Purge → permanent delete and **also delete listing images from Cloudinary** (by `public_id`)

### Security hardening (minimum)
- **Rate limiting thresholds (recommended defaults):**
  - login: **5/min per IP + 5/min per email**
  - forgot-password: **3/hr per email + 3/hr per IP**
  - register: **3/hr per IP**

### Smart caching (avoid stale mobile data)
Goal: reduce database load and improve perceived speed, while ensuring the app does not see outdated data for long.

**Approach (MVP-friendly)**
- Cache only **read-heavy** endpoints.
- Use **TTL-based caching** so data refreshes automatically.
- Perform **explicit cache eviction** on write operations affecting cached reads.

**Recommended cache store**
- Use Redis (Upstash) as cache backend (preferred for Render free-tier).

**What to cache + default TTLs**
- Categories (`GET /api/v1/categories`): TTL **15 minutes**
- Listing detail (`GET /api/v1/listings/{id}`): TTL **2 minutes**
- Feed (`GET /api/v1/listings`): TTL **30 seconds**
- Search results (`GET /api/v1/listings/search`, nearby): TTL **30 seconds** (short TTL; many query combinations)

**Cache keys / namespaces (Redis)**
- Prefix all keys with: `campusplug:cache:`
- Categories:
  - `campusplug:cache:categories:v1`
- Listing detail:
  - `campusplug:cache:listing:v1:{listingId}`
- Feed (include query params that affect results):
  - `campusplug:cache:feed:v1:{campus}:{page}:{size}`
  - If additional filters are added later, append a stable hash: `...:{filtersHash}`
- Search / Nearby (too many combinations; always key by normalized query + hash):
  - `campusplug:cache:search:v1:{queryHash}:{page}:{size}`
  - `campusplug:cache:nearby:v1:{queryHash}:{page}:{size}`
- (Optional) Bookmarks list (if cached):
  - `campusplug:cache:bookmarks:v1:{userId}:{page}:{size}`

**Key normalization rules**
- `campus` normalized to lowercase
- `queryHash` = SHA-256 of a canonical string of query params (sorted keys, trimmed values)

**Eviction patterns (MVP-simple)**
- On listing mutation (create/update/delete/restore/sold/images):
  - delete `campusplug:cache:listing:v1:{listingId}`
  - delete `campusplug:cache:feed:v1:*`
  - delete `campusplug:cache:search:v1:*`
  - delete `campusplug:cache:nearby:v1:*`
- On category-affecting changes (listing create/delete/status change):
  - delete `campusplug:cache:categories:v1`
- On bookmark add/remove (if bookmarks list cached):
  - delete `campusplug:cache:bookmarks:v1:{userId}:*`

**Cache invalidation (must-have)**
- On listing create/update/delete/restore/sold/image attach/remove: evict
  - listing detail cache for that listing
  - feed/search caches (simple MVP: clear related cache namespaces)
- On bookmark add/remove: either
  - keep bookmark state client-side, or
  - evict user bookmark list cache (if cached)

**HTTP caching (optional)**
- Add `ETag`/`Last-Modified` for `GET /listings/{id}` and `GET /categories` to reduce mobile bandwidth.

### Hosting & environment (Render)
**IMPORTANT:** Do **not** commit secrets (passwords, full DB URLs, API keys) into Git. Keep secrets only in Render environment variables (and optionally a local `.env` that is gitignored).

**Render PostgreSQL (Frankfurt / EU Central)**
- Hostname (internal): `dpg-d5dvnneuk2gs73997qmg-a`
- Port: `5432`
- Database: `campusplug_5092`
- Username: `grandmist`
- Password: **store in Render env** as `DATABASE_URL` / `SPRING_DATASOURCE_PASSWORD` (do not write here)

**URLs (store in Render, do not commit):**
- Internal database URL (used by Render Web Service): `postgresql://grandmist:<REDACTED>@dpg-d5dvnneuk2gs73997qmg-a/campusplug_5092`
- External database URL (for local tools only): `postgresql://grandmist:<REDACTED>@dpg-d5dvnneuk2gs73997qmg-a.frankfurt-postgres.render.com/campusplug_5092`

**Deployment env vars (minimum):**
- `SPRING_PROFILES_ACTIVE=prod`
- `DATABASE_URL=<Render Internal Database URL>`
- `JWT_SECRET=<base64 or random 64+ bytes>`
- `APP_AUTH_ALLOWED_EMAIL_DOMAINS=must.ac.ug,std.must.ac.ug`
- `APP_FRONTEND_BASE_URL=<where reset-password links should send users (temporary: https://campusplug-api.onrender.com)>`

**Datasource note:** Spring Boot expects JDBC (`jdbc:postgresql://...`). If Render provides `DATABASE_URL=postgresql://...`, the app must either (A) convert it at startup or (B) set `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` instead.

**Security note:** The DB password was shared in chat. Rotate it in Render if you plan to keep this repo public.

> NOTE: This `planning.md` is the API blueprint. Implementation should not deviate without explicit instruction.

---

## 1) UI-driven requirements (derived from provided screens)

This section turns the UI into explicit backend requirements.

### 1.1 Login / Register / Forgot Password (Auth UI)
**Observed UI elements**
- Login requires **University Email** + Password
- "Forgot Password?"
- "Register" link

**Observed (Create Account screen)**
- Fields: **Full Name**, **Registration Number**, **University Email**, **Tel**, **Password**, **Confirm Password**
- Registration number placeholder uses the canonical format: `YYYY/PROGRAM/NNN(/PS)?` (e.g., `2023/BIT/216/PS`)

**Backend requirements**
- Enforce allowed email domains
- Provide password reset flow (email token)
- Return auth token + minimal profile payload on login to render "Hi {name}" home screen

**Registration endpoint requirements (`POST /api/v1/auth/register`)**
- Request fields:
  - `fullName` (required)
  - `registrationNumber` (required; validated + normalized per Section 0)
  - `email` (required; must be in allowed domains)
  - `phoneNumber` (optional; if provided must be valid E.164, e.g. `+256700000000`)
  - `password` (required)
  - `confirmPassword` (required; must match `password`)
- Normalization (server-side):
  - `email`: trim + lowercase
  - `registrationNumber`: trim + normalize to canonical `YYYY/PROGRAM/NNN(/PS)?`
  - `phoneNumber`: trim; remove inner spaces before validation (e.g., `+256 700 000000` → `+256700000000`)
- Password policy (MVP-safe, not over-strict):
  - min length **8**
  - max length **72** (BCrypt safe bound)
- Response returns:
  - JWT token
  - user summary (id, fullName, email, registrationNumber, phoneNumber)

**Acceptance criteria**
- User cannot register/login with non-allowed domain
- Password reset token expires and is single-use
- Registration rejects password mismatch (`password` != `confirmPassword`)
- Registration rejects invalid `phoneNumber` format when provided
- Registration rejects duplicate `email` (409)
- Registration rejects duplicate `registrationNumber` (409)

---

### 1.2 Home feed ("Browse") / Listing cards
**Observed**
- Feed contains listing image, title, price (UGX), location line (`Wandegeya • Kampala`)
- Action buttons: **Message**, **Share**, **Bookmark**
- Listing shows a "New" badge (implies createdAt-based labels)

**Backend requirements**
- Feed endpoint returns:
  - primary image, price, title
  - location display string
  - `createdAt` to compute "New" badge (client-side)
  - whether current user bookmarked it (optional; can be separate call for MVP)

**Acceptance criteria**
- Feed returns only `ACTIVE` listings by default
- Feed paginated and stable sorting (`createdAt desc, id desc`)

---

### 1.3 Sell an item (Create Listing screen)
**Observed**
- Upload photos: **up to 10**
- Fields: Title, Price, Category dropdown, Description with **0/500** counter
- Location toggle: **Use registered location** OR enter alternate location (`City, Region`)
- Submit: "Post Listing"

**Backend requirements**
- Listing create supports:
  - `title` (required)
  - `price` (required, UGX only)
  - `category` (required)
  - `description` (optional, max **500 chars**)
  - location:
    - if `useRegisteredLocation=true`: use user’s registered location (label + point)
    - else allow alternate:
      - `locationText` (e.g., "City, Region")
      - `lat/lng` supported; nearby/distance features require it
- Images: client direct-upload to Cloudinary, then attach metadata to listing (max **10**)

**Acceptance criteria**
- Create listing rejects >10 images
- Description >500 returns 400 with field error
- If alternate location is used and `lat/lng` missing, listing still saves but nearby/distance may be unavailable (documented behavior)

---

### 1.4 Saved Items (Bookmarks screen)
**Observed**
- List of saved items
- Each item shows status badge: **Available** / **Sold**
- Trash icon to remove bookmark
- "Chat Now" button (disabled/greyed when SOLD)

**Backend requirements**
- Bookmarks list returns listing summary + listing status
- Bookmark delete is idempotent
- Conversations can be started from bookmark (create conversation thread)

**Acceptance criteria**
- For SOLD listings, API still returns bookmarked item but status indicates SOLD
- Backend blocks sending chat messages for SOLD listings (see Section 0)
- Removing bookmark removes it from list immediately

---

### 1.5 My Listings screen
**Observed**
- Tabs/filters: **All / Active / Sold / Deleted**
- For ACTIVE: actions **Edit**, **Delete**
- For SOLD: edit disabled
- For DELETED: actions **Restore**, **Purge**

**Backend requirements**
- Support filtered listing retrieval by status for current user
- Soft delete: set status `DELETED`
- Restore: `DELETED -> ACTIVE`
- Purge: permanent delete (hard delete) allowed only for owner and only if status `DELETED`

**Acceptance criteria**
- Purge endpoint cannot purge ACTIVE/SOLD
- Restore endpoint cannot restore SOLD
- Owner-only for all actions

---

### 1.6 Categories screen
**Observed**
- Grid of categories (Electronics, Stationery, Bakery, Clothing, Fast Food, Beverages, Home & Living, Beauty)
- Each shows item count (e.g., "120 items") and tag badges like "HOT"/"SALE"

**Backend requirements**
- Provide categories endpoint returning:
  - `code` (stable enum/string)
  - `displayName`
  - `coverImageUrl` or `icon` (can be static)
  - `activeListingCount`
  - optional `badge` (e.g., HOT/SALE) computed by rules (simple MVP: configured list or based on count)

**Acceptance criteria**
- Counts reflect ACTIVE listings only
- Endpoint response cacheable (10–30 minutes)

---

### 1.7 Profile / Account screens
**Observed**
- Profile shows:
  - Avatar
  - Name editable
  - Tel editable
  - Registered location editable (“Kihumuro Campus”)
  - Email and RegNo locked (lock icon)
- Account menu:
  - Profile
  - My Listings
  - Bookmarks
  - Logout

**Backend requirements**
- Users can update:
  - `fullName`
  - `phoneNumber`
  - `registeredLocation` (label + lat/lng)
  - profile photo (optional for MVP)
- Email + registration number are **immutable** in MVP
- Logout must revoke JWT

**Acceptance criteria**
- Profile update does not allow email/regNo changes
- Logout makes token unusable immediately (revocation enforced)

---

### 1.8 Messaging screens (Conversation list + Chat view)
**Observed**
- Conversation list with:
  - search input
  - each row has name, last message preview, timestamp, unread indicator dot
- Chat view:
  - "Active now" + online dot (presence)
  - message bubbles, timestamps
  - input + send button
  - attachment button shown, but MVP is text-only

**Backend requirements**
- WebSocket real-time for new messages (+ read receipts optional)
- Presence:
  - derive "Active now" from Redis TTL (preferred)
  - fallback to `lastActive` timestamp
- Fallback when WS fails:
  - **Long HTTP polling** for new messages (server waits; client reconnects immediately)
- Conversation list must include:
  - counterpart user summary
  - last message snippet
  - last message time
  - unread count (optional but matches UI)

**Acceptance criteria**
- Only participants can read/send messages
- When WS is down, long polling still delivers messages (new messages return immediately; otherwise returns on timeout)
- Sending is blocked for SOLD-linked conversations (see Section 0)

---

## 2) Non-goals for MVP (explicitly out)
- Payments / escrow
- Delivery logistics
- In-chat media messages (text-only)
- Admin dashboards
- Voice/phone calls (icon may exist in UI but no backend support in MVP)

---

## 3) Implementation phases (technical requirements + testing criteria)

### Phase 1 — Project bootstrap + CI + local dev
**Phase done rule:** meet all testing criteria, then append a Phase 1 entry to `reflector.md`.
**Goal:** runnable Spring Boot app + PostGIS locally + CI pipeline.

**Deliverables**
- Spring Boot **3.5.9** (Maven) (current stable; avoid SNAPSHOT builds)
- Dependencies:
  - web, validation, security, data-jpa
  - postgres driver
  - flyway
  - actuator
  - springdoc-openapi
  - redis (optional but recommended for logout/presence/rate limiting)
  - testcontainers
- Local `docker-compose.yml`:
  - `postgis/postgis` (Postgres + PostGIS)
  - `redis` (optional)

- Repo scripts/docs for zero-touch local run (choose one):
  - `./mvnw spring-boot:run` + `docker compose up -d`
  - or a single helper script (e.g., `scripts/dev.ps1`) that runs both

**Testing criteria**
- ✅ `mvn test` passes in CI
- ✅ Context load test using Testcontainers PostGIS
- ✅ `GET /actuator/health` returns 200
- ✅ Fresh clone + one-command run starts API and applies Flyway migrations

---

### Phase 2 — Schema + migrations (PostGIS enabled)
**Phase done rule:** meet all testing criteria, then append a Phase 2 entry to `reflector.md`.
**Goal:** DB schema aligned to UI features.

**Deliverables**
- Flyway migrations:
  - `CREATE EXTENSION IF NOT EXISTS postgis;` (**required; runs automatically on startup—no Render console steps**)
  - tables: users, listings, listing_images, bookmarks, conversations, messages
- Listing location columns:
  - `location_text` (string shown in UI)
  - `campus` (string or FK)
  - `geo` as `geography(Point, 4326)` (for PostGIS distance)
- Indexes:
  - listings `(status, created_at desc)`
  - listings `GIN` full-text (Phase 7)
  - `GIST` index on `geo`
  - bookmarks unique `(user_id, listing_id)`

**Testing criteria**
- ✅ Flyway runs on clean DB
- ✅ PostGIS functions usable (smoke query)
- ✅ Constraints enforced (email/regNo unique)

---

### Phase 3 — Auth (domain restriction + long JWT + revocation + rate limiting)
**Phase done rule:** meet all testing criteria, then append a Phase 3 entry to `reflector.md`.
**Goal:** match login UI + secure session invalidation.

**Deliverables**
- Endpoints:
  - register/login/logout
  - forgot/reset password
  - verify/resend email (optional feature; verification itself is optional in MVP)
- Security config cleanup (removes current Render warning from `UserDetailsServiceAutoConfiguration`):
  - Replace bootstrap `permitAll` config with a **stateless JWT** security setup.
  - Disable interactive auth mechanisms (`formLogin`, `httpBasic`) so Spring does not create a default dev user.
  - Keep these endpoints public:
    - `GET /` (root)
    - `GET /actuator/health`
    - `POST /api/v1/auth/*` (register/login/forgot/reset)
  - Require a valid JWT for all other endpoints.
- Domain restriction: `allowed-email-domains`
- Registration number validation:
  - accept `2023/BIT/216/PS` and `2023BIT216PS`
  - normalize and store canonical form
- Register request validation:
  - `fullName` required
  - `phoneNumber` optional; if present must be E.164
  - `password` + `confirmPassword` required and must match
  - password length 8..72
- Password storage: BCrypt (strength ~12)
- Uniqueness constraints:
  - `email` unique (case-insensitive check recommended)
  - `registrationNumber` unique (canonical form)
- Logout revocation (Redis):
  - store `revoked:jti` with TTL = remaining token life
- Rate limiting (Redis):
  - login: 5/min per IP + 5/min per email
  - forgot-password: 3/hr per email + 3/hr per IP
  - register: 3/hr per IP

**Testing criteria**
- ✅ invalid domain rejected
- ✅ regNo format validated
- ✅ password mismatch rejected
- ✅ weak/too-short password rejected
- ✅ invalid phoneNumber rejected (when provided)
- ✅ duplicate email rejected (409)
- ✅ duplicate registrationNumber rejected (409)
- ✅ logout rejects revoked token
- ✅ rate limit triggers return 429
- ✅ prod startup logs do **not** show a generated dev password warning (`UserDetailsServiceAutoConfiguration`)

---

### Phase 4 — Users (profile + registered location)
**Phase done rule:** meet all testing criteria, then append a Phase 4 entry to `reflector.md`.
**Goal:** support profile screen.

**Deliverables**
- `GET/PUT /users/profile`
- Update fields: name, phone, registered location (label + lat/lng)
- Immutable: email, regNo

**Testing criteria**
- ✅ forbidden field updates rejected
- ✅ location update persists and is used by listing creation when toggle enabled

---

### Phase 5 — Listings + My Listings actions
**Phase done rule:** meet all testing criteria, then append a Phase 5 entry to `reflector.md`.
**Goal:** implement "Sell" + "My Listings" UI.

**Deliverables**
- Create listing:
  - validates title/category/price/description(<=500)
  - status `PENDING` then auto set `ACTIVE`
  - location: use registered or alternate
- My listings list:
  - filter by status: ALL/ACTIVE/SOLD/DELETED
- Actions:
  - edit (ACTIVE only)
  - delete -> DELETED
  - restore (DELETED -> ACTIVE)
  - purge (hard delete, DELETED only)
  - mark sold (ACTIVE -> SOLD)

**Testing criteria**
- ✅ status transition rules enforced
- ✅ purge restricted to DELETED
- ✅ owner-only access

---

### Phase 6 — Images (Cloudinary direct upload) up to 10
**Phase done rule:** meet all testing criteria, then append a Phase 6 entry to `reflector.md`.
**Goal:** match "Add up to 10 photos" UI.

**Deliverables**
- `POST /uploads/cloudinary/signature`
- `POST /listings/{id}/images` attach metadata
- enforce max 10 images per listing

**Testing criteria**
- ✅ cannot attach >10
- ✅ only owner can attach/remove images

---

### Phase 7 — Categories + Search + Nearby (PostGIS)
**Phase done rule:** meet all testing criteria, then append a Phase 7 entry to `reflector.md`.
**Goal:** match categories + search bar + distance UI.

**Deliverables**
- `GET /categories` returns display metadata + active listing counts
- Search:
  - keyword + filters (category, price range, campus)
  - full-text ranking
- Nearby:
  - `lat/lng/radiusKm` with PostGIS `ST_DWithin`
- Distance label support:
  - return `distanceMeters` when user location is provided

**Testing criteria**
- ✅ counts reflect ACTIVE listings
- ✅ nearby sorted by distance
- ✅ search uses indexes (basic explain smoke check)
- ✅ cached endpoints respect TTL (data refreshes after expiry)

---

### Phase 8 — Bookmarks (Saved Items UI)
**Phase done rule:** meet all testing criteria, then append a Phase 8 entry to `reflector.md`.
**Goal:** saved items list + remove + chat action.

**Deliverables**
- `POST/GET/DELETE /bookmarks`
- Bookmarks list returns listing summary + status

**Testing criteria**
- ✅ remove bookmark idempotent
- ✅ sold listing still appears with SOLD status

---

### Phase 9 — Messaging (WebSocket + polling fallback + presence)
**Phase done rule:** meet all testing criteria, then append a Phase 9 entry to `reflector.md`.
**Goal:** match Messages UI + chat view.

**Deliverables**
- WebSocket (Spring WebSocket + STOMP):
  - connect with JWT
  - server pushes `new_message`
  - server pushes `listings.new` events (see “Realtime product/listing notifications”)
- HTTP fallback:
- HTTP fallback (long polling):
  - `GET /conversations/{id}/messages/long-poll?afterMessageId=...&timeoutSeconds=...`
    - returns as soon as there are new messages after the cursor, else returns empty on timeout
  - send via `POST /conversations/{id}/messages`
- Business rule enforcement:
  - block sending for SOLD-linked conversations
- Conversation list:
  - last message preview, last timestamp
  - unread indicator (unread count optional but recommended)
- Presence:
  - Redis TTL heartbeat on WS connect/ping
  - fallback to `lastActive`
- Validation:
  - message length max 2000 chars

**Testing criteria**
- ✅ participant-only security
- ✅ long polling returns immediately when new messages arrive
- ✅ long polling returns empty response on timeout (no new messages)
- ✅ SOLD rule enforced (send blocked)
- ✅ message length validation returns 400
- ✅ new ACTIVE listing publishes one `listings.new` event to the correct topic(s)

---

### Phase 10 — Observability + hardening
**Phase done rule:** meet all testing criteria, then append a Phase 10 entry to `reflector.md`.
**Goal:** production readiness on free tier.

**Deliverables**
- Rate limiting for auth endpoints (Redis)
- Redis-backed caching for read-heavy endpoints with TTL + eviction rules (see “Smart caching”)
- CORS restricted to frontend domain(s)
- Sentry integration (optional but recommended)
- Actuator locked down (health public)

**Testing criteria**
- ✅ secrets not logged
- ✅ rate limit triggers on repeated login failures
- ✅ cache eviction happens on listing mutations (no long-lived stale listing detail)

---

### Phase 11 — Deploy to Render (Free)
**Phase done rule:** meet all testing criteria, then append a Phase 11 entry to `reflector.md`.
**Goal:** public online URL.

**Deliverables**
- Render Web Service:
  - build: `./mvnw clean package -DskipTests`
  - start: `java -jar target/*.jar`
- Render Postgres + migrations (Flyway)
- Env vars configured (DB, JWT secret, Cloudinary, Resend, Redis)

**Testing criteria**
- ✅ `/actuator/health` OK on Render
- ✅ login works with real domain restriction
- ✅ WS works when service is awake; fallback works when sleeping
- ✅ Deploy is repeatable: new Render environment starts cleanly with Flyway (no manual DB setup)

--- 

## 4) Definition of Done (portfolio-ready MVP)
- Deployed on Render with public URL
- OpenAPI/Swagger available (protected or disabled in prod)
- Postman collection + README documented flows:
  - register/login/logout
  - create listing + attach images
  - bookmark + list bookmarks
  - start conversation + send messages (WS + fallback)
- CI runs tests; DB migrations reproducible

## Current codebase baseline (from `pom.xml`)
**Runtime / platform**
- Java: **17**
- Spring Boot parent: **3.5.9**
- Build tool: **Maven**

**API & docs**
- REST: `spring-boot-starter-web`
- Validation: `spring-boot-starter-validation`
- OpenAPI/Swagger UI: `springdoc-openapi-starter-webmvc-ui` (**2.8.5**)

**Security**
- Spring Security: `spring-boot-starter-security`
  - Note: default auto-config may generate a dev password unless replaced by our JWT setup (Phase 3).

**Database**
- JPA/Hibernate: `spring-boot-starter-data-jpa`
- PostgreSQL driver: `org.postgresql:postgresql` (runtime)

**Migrations (no manual setup)**
- Flyway is already included:
  - `org.flywaydb:flyway-core`
  - `org.flywaydb:flyway-database-postgresql` (runtime)
- Implication: DB schema must be managed via SQL migrations in:
  - `src/main/resources/db/migration`
- Render deploys should apply migrations automatically on app startup.

**Caching / rate-limit storage**
- Redis client: `spring-boot-starter-data-redis`
  - Implication: we can use Redis for refresh-token/session revocation, rate limiting, caching.

**Realtime**
- WebSocket support: `spring-boot-starter-websocket`

**Observability**
- Actuator: `spring-boot-starter-actuator` (health/readiness/liveness)

**Testing**
- Spring Boot test: `spring-boot-starter-test`
- Spring Security test: `spring-security-test`
- Testcontainers BOM: **1.20.4**
  - `testcontainers:junit-jupiter`
  - `testcontainers:postgresql`
- Implication: integration tests can run against real Postgres in CI.

### Push notifications (FCM) — New listings (mobile OS notifications)
**Goal:** notify users of new ACTIVE listings even when the app is closed.

**Provider**
- Firebase Cloud Messaging (FCM)

**Setup (infra)**
- Firebase project created
- Backend uses Firebase Admin via a service account JSON stored as Render env var:
  - `FCM_SERVICE_ACCOUNT_B64` (Base64 of service account JSON)
  - optional: `FCM_ENABLED=true`

**Device registration**
- Endpoint: `POST /api/v1/push/register` (JWT required)
  - body: `{ "token": "<fcmToken>", "platform": "android|ios", "campus": "<optional>" }`
  - store per-user tokens (or rely on topic subscriptions)

**Delivery strategy (recommended)**
- Use FCM topics by campus:
  - `campus_{campus}` (campus normalized lowercase)
- App subscribes to `campus_{userCampus}`.
- On listing transition to `ACTIVE`, backend sends push to that topic.

**Acceptance criteria**
- ✅ creating an ACTIVE listing triggers exactly one push to the correct campus topic
- ✅ secrets are not logged and are not committed to git
- ✅ feature can be disabled via env flag without code changes

## Code organization (mandatory)

## Execution guardrail (mandatory)
To prevent implementation drift, every completed phase must end with an update to:
- `reflector.md` (append a “Phase Reflection” entry)

Phase work must be checked off in:
- `checklist.md` (phase gate checklist mirroring Deliverables + Testing criteria)

Each reflection must explicitly compare the phase’s **Deliverables** + **Testing criteria** against what was actually implemented.
If anything deviates, update `planning.md` to match (this file is the source of truth).

**Root package:** `com.campusplug.api`

**Rule:** organize by **feature** (vertical slicing), not only by layer.

**Enforcement:** all Java classes/interfaces must declare packages starting with `com.campusplug.api.*` (no other base packages).

**Required package layout (guideline)**
- `com.campusplug.api.common` (shared errors/validation/utilities)
- `com.campusplug.api.config` (app config: CORS, OpenAPI, Jackson)
- `com.campusplug.api.security` (JWT, Spring Security config, rate limiting)
- Feature modules:
  - `com.campusplug.api.auth`
  - `com.campusplug.api.users`
  - `com.campusplug.api.listings`
  - `com.campusplug.api.bookmarks`
  - `com.campusplug.api.messaging`
  - `com.campusplug.api.push`

**Constraint:** the `@SpringBootApplication` class must be in `com.campusplug.api` to ensure component scanning covers all subpackages.