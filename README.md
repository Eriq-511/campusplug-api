# CampusPlug API

Spring Boot backend for the CampusPlug marketplace.

## Requirements
- Java 17
- Docker Desktop (for local PostGIS/Redis)

## Local run (one-time)
1. Start dependencies:
   - `docker compose up -d`
2. Run the API:
   - `mvn spring-boot:run`
3. Verify:
   - `GET http://localhost:8080/actuator/health`

## Notes
- Spring Boot version is pinned to 3.5.9 (see `pom.xml`).
- Flyway runs automatically on startup (`src/main/resources/db/migration`).
- Security is temporarily `permitAll` during bootstrap; Phase 3 implements JWT auth per `planning.md`.
