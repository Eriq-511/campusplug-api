<!-- markdownlint-disable MD022 MD032 MD036 -->

# CampusPlug — Phase Completion Checklist

Purpose: a phase “gate” that must be checked off before moving to the next phase in `planning.md`.

Rules
- Each checkbox should be objectively verifiable (files exist, endpoints exist, commands run).
- If you change `planning.md` deliverables/testing criteria, update this checklist to match.
- Phase completion requires:
  - all items for that phase checked
  - a matching Phase Reflection appended to `reflector.md`
- Items explicitly marked **(Optional)** do **not** block phase completion.

---

## Phase 1 — Project bootstrap + CI + local dev

**Deliverables**
- [x] Spring Boot 3.5.9 + Java 17 + Maven Wrapper works
- [x] Dependencies present: web, validation, security, data-jpa, postgres, flyway, actuator, springdoc-openapi, redis (optional), testcontainers
- [x] Local `docker-compose.yml` includes `postgis/postgis` and (optionally) `redis`
- [x] Zero-touch local run documented (either `./mvnw spring-boot:run` + `docker compose up -d`, or helper script)

**Testing criteria**
- [x] CI runs `mvn test` (or equivalent) successfully
- [x] Context-load test runs with Testcontainers PostGIS
- [x] `GET /actuator/health` returns 200 locally
- [x] Fresh clone + one-command run applies Flyway migrations

**Evidence (fill in)**
- Commands run:
  - [x] `docker compose up -d`
  - [x] `./mvnw test`
  - [x] `./mvnw spring-boot:run`
- Notes: Verified on 2026-01-18 (Windows PowerShell + Docker Desktop)

---

## Phase 2 — Schema + migrations (PostGIS enabled)

**Deliverables**
- [x] Flyway migration enables PostGIS: `CREATE EXTENSION IF NOT EXISTS postgis;`
- [x] Tables created via Flyway: users, listings, listing_images, bookmarks, conversations, messages
- [x] Listing location columns exist:
  - [x] `location_text`
  - [x] `campus`
  - [x] `geo` as `geography(Point, 4326)`
- [x] Indexes exist:
  - [x] listings `(status, created_at desc)`
  - [x] `GIST` on `geo`
  - [x] bookmarks unique `(user_id, listing_id)`

**Testing criteria**
- [x] Flyway runs on a clean DB
- [x] PostGIS functions usable (smoke query)
- [x] Constraints enforced (email/regNo unique)

**Evidence (fill in)**
- Migration files:
- Smoke query executed:

---

## Phase 3 — Auth (domain restriction + long JWT + revocation + rate limiting)

**Deliverables**
- [x] Endpoints implemented:
  - [x] `POST /api/v1/auth/register`
  - [x] `POST /api/v1/auth/login`
  - [x] `POST /api/v1/auth/logout`
  - [x] `POST /api/v1/auth/forgot-password`
  - [x] `POST /api/v1/auth/reset-password`
  - [ ] (Optional) verify/resend email endpoints
- [x] Security config is stateless JWT (no interactive auth):
  - [x] `formLogin` disabled
  - [x] `httpBasic` disabled
  - [x] Only public endpoints: `GET /`, `GET /actuator/health`, `POST /api/v1/auth/*`
  - [x] All other endpoints require JWT
- [x] Domain restriction enforced via `APP_AUTH_ALLOWED_EMAIL_DOMAINS`
- [x] Registration number validation + normalization implemented
- [x] Validation rules implemented (fullName, E.164 phone optional, password+confirm, 8..72)
- [x] Password stored with BCrypt (~12)
- [x] Uniqueness enforced: email + registrationNumber
- [x] Logout revocation works via Redis (`revoked:jti` with TTL)
- [x] Rate limiting implemented via Redis:
  - [x] login 5/min per IP + 5/min per email
  - [x] forgot-password 3/hr per email + 3/hr per IP
  - [x] register 3/hr per IP

**Testing criteria**
- [x] Invalid domain rejected
- [x] regNo format validated
- [x] password mismatch rejected
- [x] weak/too-short password rejected
- [x] invalid phone rejected (when provided)
- [x] duplicate email rejected (409)
- [x] duplicate regNo rejected (409)
- [x] logout rejects revoked token
- [x] rate limit returns 429
- [x] Prod logs do not show generated dev password warning (`UserDetailsServiceAutoConfiguration`)

**Evidence (fill in)**
- Manual test notes / Postman:

---

## Phase 4 — Users (profile + registered location)

**Deliverables**
- [x] `GET /users/profile` implemented
- [x] `PUT /users/profile` implemented
- [x] Updateable: name, phone, registered location (label + lat/lng)
- [x] Immutable: email, regNo

**Testing criteria**
- [x] Forbidden field updates rejected
- [x] Location update persists and is used by listing creation when toggle enabled

---

## Phase 5 — Listings + My Listings actions

**Deliverables**
- [x] Create listing validates title/category/price/description (<=500)
- [x] Create listing status `PENDING` then auto `ACTIVE`
- [x] Location supports registered vs alternate
- [x] My listings list supports status filter ALL/ACTIVE/SOLD/DELETED
- [x] Actions implemented with rules:
  - [x] edit (ACTIVE only)
  - [x] delete -> DELETED
  - [x] restore (DELETED -> ACTIVE)
  - [x] purge (hard delete, DELETED only)
  - [x] mark sold (ACTIVE -> SOLD)

**Testing criteria**
- [x] Status transition rules enforced
- [x] Purge restricted to DELETED
- [x] Owner-only access

---

## Phase 6 — Images (Cloudinary direct upload)

**Deliverables**
- [x] `POST /uploads/cloudinary/signature`
- [x] `POST /listings/{id}/images` attaches metadata
- [x] Max images per listing enforced: 10
- [x] Max file size per image enforced (Cloudinary): 10 MB

**Testing criteria**
- [x] Cannot attach >10 images
- [x] Only owner can attach/remove images

---

## Phase 7 — Categories + Search + Nearby (PostGIS)

**Deliverables**
- [x] `GET /categories` returns metadata + ACTIVE counts
- [x] Search supports keyword + filters and ranks results
- [x] Nearby supports `lat/lng/radiusKm` and uses `ST_DWithin`
- [x] Returns `distanceMeters` when user location is provided

**Testing criteria**
- [x] Counts reflect ACTIVE
- [x] Nearby sorted by distance
- [x] Search uses indexes (basic explain smoke check)
- [x] Cached endpoints respect TTL

---

## Phase 8 — Bookmarks (Saved Items UI)

**Deliverables**
- [x] `POST /bookmarks`
- [x] `GET /bookmarks`
- [x] `DELETE /bookmarks`
- [x] Bookmarks list returns listing summary + status

**Testing criteria**
- [x] Remove bookmark idempotent
- [x] SOLD listing still appears with SOLD status

---

## Phase 9 — Messaging (WebSocket + polling fallback + presence)

**Deliverables**
- [x] WebSocket + STOMP:
  - [x] Connect with JWT
  - [x] Push `new_message`
  - [x] Push listings.new events
- [x] Long polling endpoint implemented:
  - [x] `GET /conversations/{id}/messages/long-poll?afterMessageId=...&timeoutSeconds=...`
- [x] Send message endpoint:
  - [x] `POST /conversations/{id}/messages`
- [x] SOLD-linked conversations block sending
- [x] Conversation list includes last message preview + timestamp (+ unread optional)
- [x] Presence via Redis TTL heartbeat (+ fallback)
- [x] Message length max 2000

**Testing criteria**
- [x] Participant-only security
- [x] Long poll returns immediately when new messages arrive
- [x] Long poll returns empty on timeout
- [x] SOLD rule enforced
- [x] Message length validation returns 400
- [x] New ACTIVE listing publishes exactly one `listings.new` event to correct topic(s)

---

## Phase 10 — Observability + hardening

**Deliverables**
- [x] Rate limiting enabled for auth endpoints
- [x] Redis caching enabled with TTL + eviction rules
- [x] CORS restricted to frontend domain(s)
- [x] Actuator locked down (health public)
- [ ] (Optional) Sentry integrated

**Testing criteria**
- [x] Secrets not logged
- [x] Rate limit triggers on repeated login failures
- [x] Cache eviction occurs on listing mutations

**Evidence (fill in)**
- [x] `./mvnw.cmd -Dtest=Phase10ObservabilityHardeningIntegrationTest test` (BUILD SUCCESS, 2026-01-19)

---

## Phase 11 — Deploy to Render (Free)

**Deliverables**
- [ ] Render Web Service configured with Docker build/run and boots successfully
- [ ] Render Postgres configured; Flyway migrations apply automatically
- [ ] Env vars configured (DB, JWT secret, Cloudinary, Resend, Redis)

**Testing criteria**
- [ ] `/actuator/health` OK on Render
- [ ] Login works with real domain restriction
- [ ] WS works when service awake; fallback works when sleeping
- [ ] Deploy is repeatable from a clean environment

---

## Final gate (Definition of Done)
- [ ] Deployed on Render with public URL
- [ ] OpenAPI/Swagger available (protected or disabled in prod)
- [ ] Postman collection + README documents flows
- [ ] CI runs tests; DB migrations reproducible
- [ ] `reflector.md` contains Phase Reflections for all completed phases
