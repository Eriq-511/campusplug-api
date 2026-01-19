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

## Deploy on Render

Render's Web Service runtime list may not show a "Java" option. Deploy this project as a Docker service instead.

1. Create a new **Web Service** from your GitHub repo.
2. Set **Language** to **Docker**.
3. Ensure **Region** matches your Postgres region (e.g., Frankfurt).
4. Add environment variables (use your Render Postgres *internal* connection details):
   - `SPRING_PROFILES_ACTIVE=prod`
   - `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME=<user>`
   - `SPRING_DATASOURCE_PASSWORD=<password>`
5. Deploy, then verify:
   - `GET https://<your-service>.onrender.com/actuator/health`

## Postman

Import the collection and environment from the [postman/](postman/) folder:

- [postman/CampusPlug%20API.postman_collection.json](postman/CampusPlug%20API.postman_collection.json)
- [postman/CampusPlug%20Local.postman_environment.json](postman/CampusPlug%20Local.postman_environment.json)

## Notes

- Spring Boot version is pinned to 3.5.9 (see `pom.xml`).
- Flyway runs automatically on startup (`src/main/resources/db/migration`).
- Security is temporarily `permitAll` during bootstrap; Phase 3 implements JWT auth per `planning.md`.
