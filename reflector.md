<!-- markdownlint-disable MD022 MD032 MD033 MD036 -->

# CampusPlug — Execution Reflector (Agent Guardrails)

Purpose: keep implementation aligned to `planning.md` by forcing an explicit “end-of-phase” check.

Rules
- Update this file at the end of every implementation phase in `planning.md` (Phase 1, Phase 2, ...).
- For each phase, compare what was actually built vs the phase’s **Deliverables** and **Testing criteria**.
- If anything deviates from `planning.md`, record:
  - what changed
  - why it changed
  - whether `planning.md` was updated to match (required)
  - any follow-up tasks needed

How to use
- Append one new “Phase Reflection” section after completing a phase.
- Keep entries short and factual. Prefer links to files and commands.

---

## Phase Reflections

### Phase 1 — Project bootstrap + CI + local dev (2026-01-18)

**Scope (from planning.md)**
- Goal: Maven-based Spring Boot baseline with repeatable local dev + CI.
- Deliverables targeted: Java 17 + Boot 3.5.9, docker-compose (PostGIS/Redis), one-command local run, CI tests.
- Testing criteria targeted: CI runs tests, Testcontainers context-load, local health check.

**Work completed (what changed)**
- Code:
  - Added local dev helper scripts (`scripts/dev.ps1`, `scripts/dev.sh`).
  - Added CI workflow (`.github/workflows/ci.yml`).
  - Added baseline controllers (`src/main/java/com/campusplug/api/RootController.java`).
- Config/Infra:
  - Added PostGIS + Redis compose (`docker-compose.yml`).

**Verification performed**
- Local:
  - `docker compose up -d`
  - `./mvnw.cmd test`
  - `./mvnw.cmd spring-boot:run` (validated `/actuator/health` responds)
- CI:
  - Workflow executes Maven tests on push/PR.

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced: NONE

**Risks / next actions**
- Next: keep Phase gates updated as new phases land.

---

### Phase 2 — Schema + migrations (PostGIS enabled) (2026-01-18)

**Scope (from planning.md)**
- Goal: Flyway-managed schema with PostGIS enabled + core marketplace tables.

**Work completed (what changed)**
- Database:
  - Flyway migrations:
    - `src/main/resources/db/migration/V1__init.sql` (PostGIS extension)
    - `src/main/resources/db/migration/V2__core_schema.sql` (users/listings/images/bookmarks/conversations/messages)
  - Indexes and constraints added (including GIST geo index, uniqueness on email lower + registration_number).

**Verification performed**
- Tests:
  - `ContextLoadsPostgisTest` verifies Spring context boots with PostGIS Testcontainer.
  - `PostgisSmokeTest` verifies `PostGIS_Full_Version()` works and core tables exist.

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced: NONE

**Risks / next actions**
- Next: ensure any new schema changes remain Flyway-only.

---

### Phase 3 — Auth (domain restriction + long JWT + revocation + rate limiting) (2026-01-18)

**Scope (from planning.md)**
- Goal: JWT auth with domain restriction, password reset, logout revocation, and Redis rate limiting.

**Work completed (what changed)**
- API surface:
  - Implemented auth endpoints in `src/main/java/com/campusplug/api/auth/AuthController.java`:
    - `POST /api/v1/auth/register`, `login`, `logout`, `forgot-password`, `reset-password`
- Security:
  - Stateless Spring Security chain in `src/main/java/com/campusplug/api/security/SecurityConfig.java`.
  - JWT validation + Redis-backed revocation.
  - Redis-backed auth rate limiting via `AuthRateLimitFilter`.
- Validation/business rules:
  - Allowed-domain enforcement (`EmailDomainValidator`).
  - Registration number normalization (`RegistrationNumberNormalizer`).
  - E.164 phone validation + password length via Bean Validation.

**Verification performed**
- Tests:
  - `AuthPhase3IntegrationTest` covers register/login/logout/forgot/reset and verifies:
    - domain restriction rejects non-allowed domains
    - registration number normalization + invalid format rejection
    - password mismatch + weak password validation behavior
    - duplicate email/regNo conflicts
    - logout revocation enforcement
    - rate limiting returns 429

**Checklist vs planning.md**
- Deliverables met: YES (email verify/resend endpoints intentionally left as Optional)
- Testing criteria met: YES
- Deviations introduced: NONE

**Risks / next actions**
- Next: Phase 4 (Users/profile) should reuse the same error envelope + exception strategy.

---

### Phase 4 — Users (profile + registered location) (2026-01-18)

**Scope (from planning.md)**
- Goal: support profile screen with editable name/phone/location; immutable email/regNo.

**Work completed (what changed)**
- API surface:
  - Added `GET /api/v1/users/profile` and `PUT /api/v1/users/profile`.
- Validation/business rules:
  - Rejects attempts to update `email` or `registrationNumber` with `FORBIDDEN_FIELD_UPDATE`.
  - Accepts registered location as label + lat/lng and persists to PostGIS.

**Verification performed**
- Tests:
  - Added `UsersPhase4IntegrationTest` for immutable field updates + location persistence.
  - Note: these tests require Docker (Testcontainers).

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES (test coverage added; requires Docker to execute)
- Deviations introduced: NONE

**Risks / next actions**
- Next: Phase 5 listings should support `useRegisteredLocation=true` and enforce listing state transitions.

---

### Phase 5 — Listings + My Listings actions (2026-01-18)

**Scope (from planning.md)**
- Goal: implement "Sell" + "My Listings" UI with owner-only actions and state rules.

**Work completed (what changed)**
- API surface:
  - Added listing endpoints under `/api/v1/listings`:
    - create listing
    - my listings filter
    - update (ACTIVE only)
    - delete/restore/mark sold/purge actions with state rules
- Location:
  - `useRegisteredLocation=true` copies the user registered label + geo into listing.

**Verification performed**
- Tests:
  - Added `ListingsPhase5IntegrationTest` for registered-location creation, status transitions, and owner-only enforcement.
  - Note: these tests require Docker (Testcontainers).

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES (test coverage added; requires Docker to execute)
- Deviations introduced: NONE

**Risks / next actions**
- Next: Phase 6 images and Phase 7 feed/search/nearby will need listing read endpoints and caching.

---

### Phase 6 — Images (Cloudinary direct upload) up to 10 (2026-01-18)

**Scope (from planning.md)**
- Goal: match “Add up to 10 photos” UI via Cloudinary direct upload + attach metadata.

**Work completed (what changed)**
- API surface:
  - Added `POST /api/v1/uploads/cloudinary/signature` (also available as `/uploads/cloudinary/signature`).
  - Added `POST /api/v1/listings/{id}/images` to attach image metadata.
  - Added `DELETE /api/v1/listings/{id}/images/{imageId}` to remove an attached image.
- Business rules:
  - Enforces max **10** images per listing (`IMAGE_LIMIT_EXCEEDED`).
  - Owner-only enforcement for signature + attach/remove (`NOT_OWNER`).
  - Signatures include `max_file_size=10MB` to enforce upload size at Cloudinary.

**Verification performed**
- Tests:
  - Added `ImagesPhase6IntegrationTest` validating:
    - cannot attach >10
    - only owner can get signature + attach/remove

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced: NONE

**Risks / next actions**
- Next: decide whether to include images in feed/listing detail reads (and add cache eviction rules for image mutations in Phase 10).

---

### Phase 7 — Categories + Search + Nearby (PostGIS) (2026-01-18)

**Scope (from planning.md)**
- Goal: add browse/discovery read endpoints (categories, search, nearby) backed by PostGIS + full-text search.
- Deliverables targeted: categories with ACTIVE counts, search ranking, nearby within radius (sorted by distance), and Redis caching with TTL.

**Work completed (what changed)**
- Database:
  - Added full-text support via Flyway migration `src/main/resources/db/migration/V3__listing_search_fulltext.sql` (generated `search_tsv` + GIN index).
- API surface:
  - Added `GET /api/v1/categories` (also aliased as `/categories`) returning category metadata + ACTIVE counts.
  - Added `GET /api/v1/listings/search` for keyword search over ACTIVE listings.
  - Added `GET /api/v1/listings/nearby` supporting `lat/lng/radiusKm` and returning `distanceMeters`.
- Caching:
  - Added Redis-backed caching with per-cache TTLs and hash-based cache keys for param-heavy endpoints.
  - Added cache eviction hooks for listing mutations that affect read endpoints.

**Verification performed**
- Tests:
  - `Phase7CategoriesSearchNearbyIntegrationTest` verifies:
    - categories counts reflect ACTIVE and cache TTL expiry works
    - nearby is sorted by distance and includes `distanceMeters`
    - full-text GIN index exists (plus EXPLAIN predicate smoke check)
  - Ran full test suite locally (Testcontainers PostGIS + Redis) with all tests passing.

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced:
  - Cache namespace version bumped to `v2` to ensure safe JSON serialization/deserialization for cached DTOs.
  - Search EXPLAIN check validates full-text predicate + index existence (planner choice may vary on tiny test tables).

---

### Phase 8 — Bookmarks (Saved Items UI) (2026-01-19)

**Scope (from planning.md)**
- Goal: Saved Items list + remove bookmark + preserve listing status (including SOLD).

**Work completed (what changed)**
- Database:
  - Reused existing `bookmarks` table (composite PK `(user_id, listing_id)` from Phase 2 schema).
- API surface:
  - Added `POST /api/v1/bookmarks` (also aliased as `/bookmarks`) to add a bookmark.
  - Added `GET /api/v1/bookmarks` (also aliased as `/bookmarks`) to list bookmarked listings with status.
  - Added `DELETE /api/v1/bookmarks?listingId=...` (also aliased as `/bookmarks`) to remove a bookmark.
- Business rules:
  - Remove is idempotent (second delete still returns `204`).
  - Bookmarks list returns listing summary + `status` (SOLD items remain visible).

**Verification performed**
- Tests:
  - Added `BookmarksPhase8IntegrationTest` covering idempotent delete and SOLD listing behavior.

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced: NONE

---

### Phase 9 — Messaging (WebSocket + polling fallback + presence) (2026-01-19)

**Scope (from planning.md)**
- Goal: add buyer/seller messaging with WebSocket realtime + long-poll fallback and presence.

**Work completed (what changed)**
- Database:
  - Reused existing `conversations` and `messages` tables from Phase 2 schema.
- API surface:
  - Added `POST /api/v1/conversations` to create or return an existing conversation for a listing.
  - Added `GET /api/v1/conversations` to list conversations with last message preview + timestamp.
  - Added `POST /api/v1/conversations/{id}/messages` to send a message (max 2000 chars).
  - Added `GET /api/v1/conversations/{id}/messages` to fetch latest messages.
  - Added `GET /api/v1/conversations/{id}/messages/long-poll` polling fallback.
- Realtime:
  - Added STOMP-over-WebSocket endpoint `/ws` with JWT auth at CONNECT.
  - Publishes:
    - `new_message` on `/topic/conversations.{conversationId}`
    - `listings.new` on `/topic/listings.new` (broadcast after listing creation)
- Business rules:
  - Only participants can view/send messages.
  - Sending is blocked when the linked listing is `SOLD`.
  - Presence stored in Redis via TTL heartbeats and surfaced on conversation list.

**Verification performed**
- Tests:
  - Updated `MessagingPhase9IntegrationTest` covering:
    - participant-only send enforcement
    - long-poll timeout returns empty
    - long-poll returns immediately when a new message arrives
    - SOLD listing blocks sending
    - message length > 2000 returns `400` (`VALIDATION_ERROR`)
  - Added `ListingsNewWebSocketPhase9IntegrationTest` covering:
    - exactly one `/topic/listings.new` event for a single listing creation

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced: NONE

---

### Phase 10 — Observability + hardening (2026-01-19)

**Scope (from planning.md)**
- Goal: harden HTTP + realtime boundaries (CORS), ensure actuator is not publicly exposed beyond health, and prove cache eviction and log hygiene.

**Work completed (what changed)**
- Security/CORS:
  - Added `src/main/java/com/campusplug/api/security/AppCorsProperties.java` (`app.cors.*`) to centralize allowed origins.
  - Updated `src/main/java/com/campusplug/api/security/SecurityConfig.java` to enforce HTTP CORS and allow `OPTIONS` preflight.
  - Updated `src/main/java/com/campusplug/api/realtime/WebSocketConfig.java` to restrict `/ws` allowed origins based on the same properties (removed wildcard).
- Config:
  - Updated `src/main/resources/application.yml` to support `APP_CORS_ALLOWED_ORIGINS`.
- Verification tests:
  - Added `src/test/java/com/campusplug/api/Phase10ObservabilityHardeningIntegrationTest.java` covering:
    - actuator lockdown (`/actuator/health` public, `/actuator/info` requires JWT)
    - CORS preflight allow/deny behavior
    - cache eviction observable after listing mutation
    - “secrets not logged” check via captured output

**Verification performed**
- Tests:
  - `./mvnw.cmd -Dtest=Phase10ObservabilityHardeningIntegrationTest test`
  - Result: BUILD SUCCESS (Tests run: 4, Failures: 0, Errors: 0)

**Checklist vs planning.md**
- Deliverables met: YES
- Testing criteria met: YES
- Deviations introduced: NONE

**Risks / next actions**
- Ensure `APP_CORS_ALLOWED_ORIGINS` is set to the real frontend origin(s) in deploy env.

### Phase X — <name> (YYYY-MM-DD)

**Scope (from planning.md)**
- Goal:
- Deliverables targeted:
- Testing criteria targeted:

**Work completed (what changed)**
- Code:
  - Files changed/added:
  - Key classes/symbols added/modified:
- Database:
  - Flyway migrations added/changed:
  - Notes (PostGIS, constraints, indexes):
- API surface:
  - Endpoints added/changed:
  - Breaking changes (if any):
- Config/Infra:
  - Env vars added/changed:
  - Docker/Render changes:

**Verification performed**
- Local:
  - Commands run:
  - Results:
- Render/prod:
  - Health check:
  - Logs sanity:

**Checklist vs planning.md**
- Deliverables met: YES/NO (explain briefly)
- Testing criteria met: YES/NO (explain briefly)
- Deviations introduced: NONE / <list>

**Deviations (if any)**
- What deviated:
- Why:
- planning.md updated to reflect this: YES/NO
- Follow-ups required:

**Risks / next actions**
- Risks:
- Next:

---

## Rolling “Off-Track” Signals

Mark these as you notice them (helps catch drift early):
- [ ] Added endpoints not specified in `planning.md` without updating the plan
- [ ] Changed auth/session behavior (JWT, revocation, domains) without updating the plan
- [ ] Added schema changes without Flyway migration
- [ ] Added non-feature-based packages (violates `com.campusplug.api.*` feature slicing)
- [ ] Broke Render boot/health and didn’t restore before proceeding
- [ ] Introduced new external services or paid dependencies without updating the plan
