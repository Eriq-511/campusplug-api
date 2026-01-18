# CampusPlug — Phase Completion Checklist

Purpose: a phase “gate” that must be checked off before moving to the next phase in `planning.md`.

Rules
- Each checkbox should be objectively verifiable (files exist, endpoints exist, commands run).
- If you change `planning.md` deliverables/testing criteria, update this checklist to match.
- Phase completion requires:
  - all items for that phase checked
  - a matching Phase Reflection appended to `reflector.md`

---

## Phase 1 — Project bootstrap + CI + local dev

**Deliverables**
- [ ] Spring Boot 3.5.9 + Java 17 + Maven Wrapper works
- [ ] Dependencies present: web, validation, security, data-jpa, postgres, flyway, actuator, springdoc-openapi, redis (optional), testcontainers
- [ ] Local `docker-compose.yml` includes `postgis/postgis` and (optionally) `redis`
- [ ] Zero-touch local run documented (either `./mvnw spring-boot:run` + `docker compose up -d`, or helper script)

**Testing criteria**
- [ ] CI runs `mvn test` (or equivalent) successfully
- [ ] Context-load test runs with Testcontainers PostGIS
- [ ] `GET /actuator/health` returns 200 locally
- [ ] Fresh clone + one-command run applies Flyway migrations

**Evidence (fill in)**
- Commands run:
  - [ ] `docker compose up -d`
  - [ ] `./mvnw test`
  - [ ] `./mvnw spring-boot:run`
- Notes:

---

## Phase 2 — Schema + migrations (PostGIS enabled)

**Deliverables**
- [ ] Flyway migration enables PostGIS: `CREATE EXTENSION IF NOT EXISTS postgis;`
- [ ] Tables created via Flyway: users, listings, listing_images, bookmarks, conversations, messages
- [ ] Listing location columns exist:
  - [ ] `location_text`
  - [ ] `campus`
  - [ ] `geo` as `geography(Point, 4326)`
- [ ] Indexes exist:
  - [ ] listings `(status, created_at desc)`
  - [ ] `GIST` on `geo`
  - [ ] bookmarks unique `(user_id, listing_id)`

**Testing criteria**
- [ ] Flyway runs on a clean DB
- [ ] PostGIS functions usable (smoke query)
- [ ] Constraints enforced (email/regNo unique)

**Evidence (fill in)**
- Migration files:
- Smoke query executed:

---

## Phase 3 — Auth (domain restriction + long JWT + revocation + rate limiting)

**Deliverables**
- [ ] Endpoints implemented:
  - [ ] `POST /api/v1/auth/register`
  - [ ] `POST /api/v1/auth/login`
  - [ ] `POST /api/v1/auth/logout`
  - [ ] `POST /api/v1/auth/forgot-password`
  - [ ] `POST /api/v1/auth/reset-password`
  - [ ] (Optional) verify/resend email endpoints
- [ ] Security config is stateless JWT (no interactive auth):
  - [ ] `formLogin` disabled
  - [ ] `httpBasic` disabled
  - [ ] Only public endpoints: `GET /`, `GET /actuator/health`, `POST /api/v1/auth/*`
  - [ ] All other endpoints require JWT
- [ ] Domain restriction enforced via `APP_AUTH_ALLOWED_EMAIL_DOMAINS`
- [ ] Registration number validation + normalization implemented
- [ ] Validation rules implemented (fullName, E.164 phone optional, password+confirm, 8..72)
- [ ] Password stored with BCrypt (~12)
- [ ] Uniqueness enforced: email + registrationNumber
- [ ] Logout revocation works via Redis (`revoked:jti` with TTL)
- [ ] Rate limiting implemented via Redis:
  - [ ] login 5/min per IP + 5/min per email
  - [ ] forgot-password 3/hr per email + 3/hr per IP
  - [ ] register 3/hr per IP

**Testing criteria**
- [ ] Invalid domain rejected
- [ ] regNo format validated
- [ ] password mismatch rejected
- [ ] weak/too-short password rejected
- [ ] invalid phone rejected (when provided)
- [ ] duplicate email rejected (409)
- [ ] duplicate regNo rejected (409)
- [ ] logout rejects revoked token
- [ ] rate limit returns 429
- [ ] Prod logs do not show generated dev password warning (`UserDetailsServiceAutoConfiguration`)

**Evidence (fill in)**
- Manual test notes / Postman:

---

## Phase 4 — Users (profile + registered location)

**Deliverables**
- [ ] `GET /users/profile` implemented
- [ ] `PUT /users/profile` implemented
- [ ] Updateable: name, phone, registered location (label + lat/lng)
- [ ] Immutable: email, regNo

**Testing criteria**
- [ ] Forbidden field updates rejected
- [ ] Location update persists and is used by listing creation when toggle enabled

---

## Phase 5 — Listings + My Listings actions

**Deliverables**
- [ ] Create listing validates title/category/price/description (<=500)
- [ ] Create listing status `PENDING` then auto `ACTIVE`
- [ ] Location supports registered vs alternate
- [ ] My listings list supports status filter ALL/ACTIVE/SOLD/DELETED
- [ ] Actions implemented with rules:
  - [ ] edit (ACTIVE only)
  - [ ] delete -> DELETED
  - [ ] restore (DELETED -> ACTIVE)
  - [ ] purge (hard delete, DELETED only)
  - [ ] mark sold (ACTIVE -> SOLD)

**Testing criteria**
- [ ] Status transition rules enforced
- [ ] Purge restricted to DELETED
- [ ] Owner-only access

---

## Phase 6 — Images (Cloudinary direct upload)

**Deliverables**
- [ ] `POST /uploads/cloudinary/signature`
- [ ] `POST /listings/{id}/images` attaches metadata
- [ ] Max images per listing enforced: 10
- [ ] Max file size per image enforced (Cloudinary): 10 MB

**Testing criteria**
- [ ] Cannot attach >10 images
- [ ] Only owner can attach/remove images

---

## Phase 7 — Categories + Search + Nearby (PostGIS)

**Deliverables**
- [ ] `GET /categories` returns metadata + ACTIVE counts
- [ ] Search supports keyword + filters and ranks results
- [ ] Nearby supports `lat/lng/radiusKm` and uses `ST_DWithin`
- [ ] Returns `distanceMeters` when user location is provided

**Testing criteria**
- [ ] Counts reflect ACTIVE
- [ ] Nearby sorted by distance
- [ ] Search uses indexes (basic explain smoke check)
- [ ] Cached endpoints respect TTL

---

## Phase 8 — Bookmarks (Saved Items UI)

**Deliverables**
- [ ] `POST /bookmarks`
- [ ] `GET /bookmarks`
- [ ] `DELETE /bookmarks`
- [ ] Bookmarks list returns listing summary + status

**Testing criteria**
- [ ] Remove bookmark idempotent
- [ ] SOLD listing still appears with SOLD status

---

## Phase 9 — Messaging (WebSocket + polling fallback + presence)

**Deliverables**
- [ ] WebSocket + STOMP:
  - [ ] Connect with JWT
  - [ ] Push `new_message`
  - [ ] Push listings.new events
- [ ] Long polling endpoint implemented:
  - [ ] `GET /conversations/{id}/messages/long-poll?afterMessageId=...&timeoutSeconds=...`
- [ ] Send message endpoint:
  - [ ] `POST /conversations/{id}/messages`
- [ ] SOLD-linked conversations block sending
- [ ] Conversation list includes last message preview + timestamp (+ unread optional)
- [ ] Presence via Redis TTL heartbeat (+ fallback)
- [ ] Message length max 2000

**Testing criteria**
- [ ] Participant-only security
- [ ] Long poll returns immediately when new messages arrive
- [ ] Long poll returns empty on timeout
- [ ] SOLD rule enforced
- [ ] Message length validation returns 400
- [ ] New ACTIVE listing publishes exactly one `listings.new` event to correct topic(s)

---

## Phase 10 — Observability + hardening

**Deliverables**
- [ ] Rate limiting enabled for auth endpoints
- [ ] Redis caching enabled with TTL + eviction rules
- [ ] CORS restricted to frontend domain(s)
- [ ] Actuator locked down (health public)
- [ ] (Optional) Sentry integrated

**Testing criteria**
- [ ] Secrets not logged
- [ ] Rate limit triggers on repeated login failures
- [ ] Cache eviction occurs on listing mutations

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
