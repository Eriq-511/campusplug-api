# CampusPlug API

Spring Boot backend for the CampusPlug marketplace.

## Project guardrails (don’t drift)

- Source of truth: `planning.md`
- Phase gate checklist: `checklist.md` (must be checked off before moving to the next phase)
- Phase reflection log: `reflector.md` (append an entry at the end of every completed phase)

## Requirements

- Java 17
- Docker Desktop (optional, for local PostGIS/Redis)

## Local run (one-time)

### Option A (recommended): one command

- Windows (PowerShell): `./scripts/dev.ps1`
- macOS/Linux: `./scripts/dev.sh`

### Option B: manual (two commands)

1. Start dependencies:
   - `docker compose up -d`
2. Run the API:
   - Windows: `./mvnw.cmd spring-boot:run`
   - macOS/Linux: `./mvnw spring-boot:run`
3. Verify:
   - `GET http://localhost:8080/actuator/health`

## OTP email delivery (local)

By default, OTP and outbound email are disabled.

- Start MailHog (included in `docker-compose.yml`): `docker compose up -d`
- Enable OTP + email:
  - `APP_OTP_ENABLED=true`
  - `APP_EMAIL_ENABLED=true`
- Run the API (local profile): `./mvnw.cmd spring-boot:run`
- Open MailHog inbox UI: `http://localhost:8025`

Notes:

- Local SMTP is configured via [src/main/resources/application-local.yml](src/main/resources/application-local.yml) (host `localhost`, port `1025`).
- To send real emails via Gmail SMTP instead, set:
  - `APP_EMAIL_ENABLED=true`
  - `APP_OTP_ENABLED=true`
  - `GMAIL_USER=<your@gmail.com>`
  - `GMAIL_APP_PASSWORD=<gmail-app-password>`
  - `SPRING_MAIL_HOST=smtp.gmail.com` and `SPRING_MAIL_PORT=587`
  - `SPRING_MAIL_SMTP_AUTH=true`
  - `SPRING_MAIL_SMTP_STARTTLS_ENABLE=true`
  - `SPRING_MAIL_SMTP_STARTTLS_REQUIRED=true`

### Gmail delivery from Docker

If you run the API via Docker Compose, set Gmail credentials in a `.env` file (recommended) or your shell environment:

Create a `.env` file (do not commit):

```env
GMAIL_USER=your@gmail.com
GMAIL_APP_PASSWORD=your-gmail-app-password
```

Then run:

- `docker compose up -d --build`

The default `docker-compose.yml` is configured to use Gmail SMTP when these env vars are present.

## Deploy on Render

Render's Web Service runtime list may not show a "Java" option. Deploy this project as a Docker service instead.

1. Create a new **Web Service** from your GitHub repo.
2. Set **Language** to **Docker** (Render will build from `Dockerfile`).
3. Set **Region** close to your Neon Postgres region.
4. Add environment variables (Neon + browsers):
   - `SPRING_PROFILES_ACTIVE=render`
   - `SPRING_DATASOURCE_URL=jdbc:postgresql://<neon-host>:5432/<db>?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME=<neon-user>`
   - `SPRING_DATASOURCE_PASSWORD=<neon-password>`
   - Redis (required):
     - Option A (preferred if provided): `REDIS_TLS_URL=rediss://:<password>@<host>:6379` (or `REDIS_URL=redis://...`)
     - Option B: `SPRING_DATA_REDIS_URL=rediss://default:<password>@<host>:6379`
     - Or host/port: `SPRING_DATA_REDIS_HOST=<host>`, `SPRING_DATA_REDIS_PORT=6379`, `SPRING_DATA_REDIS_PASSWORD=<password>`, `SPRING_DATA_REDIS_SSL_ENABLED=true`
   - `JWT_SECRET=<long-random-secret>`
   - `APP_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>`
   - `APP_FRONTEND_BASE_URL=https://<your-frontend-domain>`
   - (Recommended for Neon) `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5`
5. Ensure PostGIS is enabled on Neon.
   - This app requires PostGIS (schema uses `geography(Point, 4326)` and queries use `ST_*`).
6. Deploy, then verify:
   - `GET https://<your-service>.onrender.com/actuator/health`

## Deploy on DigitalOcean

This project is already set up for Docker-based deployments (see `Dockerfile`).
The closest Render-like experience on DigitalOcean is **App Platform**.

### Option A (recommended): App Platform + Neon Postgres (PostGIS) + Redis

1. Create a **Neon** Postgres database (pick a region close to your users).
    - Create a database named `campusplug`.
    - Enable **PostGIS** for the database.
       - This app requires PostGIS and Flyway contains `CREATE EXTENSION IF NOT EXISTS postgis;`.
       - If your Neon role cannot create extensions, enable PostGIS once using a role that can.

2. Create a **Redis** instance.
   - You can self-host Redis on a Droplet, or use **Managed Redis**.
   - If you use Managed Redis, you will typically need TLS + a password. Spring Boot supports this via
     `SPRING_DATA_REDIS_SSL_ENABLED=true` and `SPRING_DATA_REDIS_PASSWORD=...`.

3. Create a new **App Platform** app from your GitHub repo.
   - Component type: **Web Service**
   - Build method: **Dockerfile**
   - Health check: `/actuator/health`
   - The container listens on `PORT` (defaults to 8080); App Platform will set `PORT` automatically.

4. Set environment variables (prod example):
   - Required core:
     - `SPRING_PROFILES_ACTIVE=prod`
       - `SPRING_DATASOURCE_URL=jdbc:postgresql://<neon-host>:5432/campusplug?sslmode=require`
     - `SPRING_DATASOURCE_USERNAME=<db-user>`
     - `SPRING_DATASOURCE_PASSWORD=<db-password>`
     - `JWT_SECRET=<long-random-secret>`
       - (Recommended for Neon) `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5`
          - Neon has connection limits; keep the pool small unless you’ve sized Neon accordingly.
   - Redis (choose one approach):
       - Option B (Upstash / managed Redis URL):
          - `SPRING_DATA_REDIS_URL=rediss://default:<password>@<host>:6379`
       - Host/port (no auth): `SPRING_REDIS_HOST=<host>`, `SPRING_REDIS_PORT=6379`
       - Host/port (managed Redis typical):
          - `SPRING_DATA_REDIS_HOST=<host>`
          - `SPRING_DATA_REDIS_PORT=<port>`
          - `SPRING_DATA_REDIS_PASSWORD=<password>`
          - `SPRING_DATA_REDIS_SSL_ENABLED=true`
       - If you do not want Redis in production, you must change cache type:
          - `SPRING_CACHE_TYPE=simple`
   - Frontend/CORS (important for browsers + WebSockets):
     - `APP_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>`
     - `APP_FRONTEND_BASE_URL=https://<your-frontend-domain>`
   - Optional integrations (only if you use them):
     - Gmail SMTP: `GMAIL_USER=...`, `GMAIL_APP_PASSWORD=...`, `APP_EMAIL_ENABLED=true`, `APP_OTP_ENABLED=true`
     - Google Maps: `GOOGLE_MAPS_API_KEY=...`
     - Cloudinary: `CLOUDINARY_CLOUD_NAME=...`, `CLOUDINARY_API_KEY=...`, `CLOUDINARY_API_SECRET=...`
     - Firebase (FCM): `FIREBASE_SERVICE_ACCOUNT_JSON=...`
       - Can be raw JSON or base64-encoded JSON (recommended for dashboards/CI).

### Database migration (Render Postgres -> DigitalOcean Postgres)

At a high level (Render -> Neon is the same idea):

1. Create the new Neon database first.
2. Take a dump from Render (or your current provider).
3. Restore into Neon.
4. Deploy the app on DigitalOcean pointing to Neon and verify:
   - `GET https://<your-domain>/actuator/health`
   - Run a small smoke test (login + a couple of listing endpoints).
5. Cut over DNS to the DO app.

If you want near-zero downtime, do the restore, deploy DO in parallel, and do DNS cutover during a short maintenance window.

Tip (Windows-friendly): if you don't have `pg_dump` installed, you can run it via Docker using a Postgres image.

### Option B: Droplet (VM) + Docker Compose

If you prefer full control (at the cost of more ops), you can run the same `Dockerfile`/`docker-compose.yml` on a Droplet,
and point `SPRING_DATASOURCE_URL` to a Managed Postgres database.

## Postman

Import the collection and environment from the [postman/](postman/) folder:

- [postman/CampusPlug%20API.postman_collection.json](postman/CampusPlug%20API.postman_collection.json)
- [postman/CampusPlug%20Local.postman_environment.json](postman/CampusPlug%20Local.postman_environment.json)

## Notes

- Spring Boot version is pinned to 3.5.9 (see `pom.xml`).
- Flyway runs automatically on startup (`src/main/resources/db/migration`).
- Flyway runs automatically on startup; if PostGIS is not enabled/allowed on your DB, startup will fail with a clear error.
