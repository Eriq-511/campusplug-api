#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-local}"

echo "Starting dependencies (PostGIS + Redis)..."
docker compose up -d

echo "Waiting for PostGIS to be ready..."
MAX_ATTEMPTS=60
for i in $(seq 1 $MAX_ATTEMPTS); do
  if docker exec campusplug-postgis pg_isready -U campusplug -d campusplug >/dev/null 2>&1; then
    echo "PostGIS is ready."
    break
  fi
  if [ "$i" -eq "$MAX_ATTEMPTS" ]; then
    echo "PostGIS did not become ready in time." >&2
    exit 1
  fi
  sleep 1
done

echo "Waiting for Redis to be ready..."
for i in $(seq 1 $MAX_ATTEMPTS); do
  if docker exec campusplug-redis redis-cli ping 2>/dev/null | grep -q PONG; then
    echo "Redis is ready."
    break
  fi
  if [ "$i" -eq "$MAX_ATTEMPTS" ]; then
    echo "Redis did not become ready in time." >&2
    exit 1
  fi
  sleep 1
done

echo "Starting API (Spring profile: $PROFILE)..."
export SPRING_PROFILES_ACTIVE="$PROFILE"

./mvnw spring-boot:run
