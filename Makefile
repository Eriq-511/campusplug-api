.DEFAULT_GOAL := help

# ─── Variables ────────────────────────────────────────────────────
PROFILE       ?= local
MVNW          := $(if $(filter Windows_NT,$(OS)),./mvnw.cmd,./mvnw)

# ─── Targets ──────────────────────────────────────────────────────

## dev: start PostGIS + Redis in Docker, then run the Spring Boot API locally
.PHONY: dev
dev:
	@echo "Starting dependencies (PostGIS + Redis)..."
	docker compose up -d db redis
	powershell -NoProfile -Command "\
		Write-Host 'Waiting for PostGIS...'; \
		$$i=0; do { $$i++; Start-Sleep 1; docker exec campusplug-postgis pg_isready -U campusplug -d campusplug | Out-Null } until ($$LASTEXITCODE -eq 0 -or $$i -ge 60); \
		Write-Host 'PostGIS ready.'; \
		Write-Host 'Waiting for Redis...'; \
		$$i=0; do { $$i++; Start-Sleep 1; $$p = docker exec campusplug-redis redis-cli ping 2>$$null } until ($$p -match 'PONG' -or $$i -ge 60); \
		Write-Host 'Redis ready.'"
	@echo "Starting API (profile: $(PROFILE))..."
	powershell -NoProfile -Command "\
		if (Test-Path .env) { \
			Get-Content .env | Where-Object { $$_ -notmatch '^\s*#' -and $$_ -match '=' } | ForEach-Object { \
				$$kv = $$_ -split '=',2; \
				[System.Environment]::SetEnvironmentVariable($$kv[0].Trim(), $$kv[1].Trim(), 'Process') \
			}; \
			Write-Host 'Loaded .env' \
		}; \
		$$env:SPRING_PROFILES_ACTIVE='$(PROFILE)'; $(MVNW) spring-boot:run"

## up: start all services (db + redis + api) via Docker Compose
.PHONY: up
up:
	docker compose up -d --build

## down: stop and remove all Docker containers
.PHONY: down
down:
	docker compose down

## logs: tail logs for all running containers
.PHONY: logs
logs:
	docker compose logs -f

## build: compile and package the app (skip tests)
.PHONY: build
build:
	$(MVNW) -q package -DskipTests

## test: run all tests
.PHONY: test
test:
	$(MVNW) test

## clean: remove Maven build artifacts
.PHONY: clean
clean:
	$(MVNW) clean

## ps: show running containers
.PHONY: ps
ps:
	docker compose ps

## help: list available make targets
.PHONY: help
help:
	@grep -E '^## ' Makefile | awk 'BEGIN {FS = ": "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}' | sed 's/## //'
