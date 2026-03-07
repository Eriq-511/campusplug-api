Param(
  [string]$Profile = "local"
)

$ErrorActionPreference = "Stop"

Write-Host "Starting dependencies (PostGIS + Redis)..."
docker compose up -d

Write-Host "Waiting for PostGIS to be ready..."
$maxAttempts = 60
for ($i = 1; $i -le $maxAttempts; $i++) {
  try {
    $result = docker exec campusplug-postgis pg_isready -U campusplug -d campusplug 2>$null
    if ($LASTEXITCODE -eq 0) {
      Write-Host "PostGIS is ready."
      break
    }
  } catch {}

  if ($i -eq $maxAttempts) {
    throw "PostGIS did not become ready in time."
  }

  Start-Sleep -Seconds 1
}

Write-Host "Waiting for Redis to be ready..."
for ($i = 1; $i -le $maxAttempts; $i++) {
  try {
    $pong = docker exec campusplug-redis redis-cli ping 2>$null
    if ($pong -match "PONG") {
      Write-Host "Redis is ready."
      break
    }
  } catch {}

  if ($i -eq $maxAttempts) {
    throw "Redis did not become ready in time."
  }

  Start-Sleep -Seconds 1
}

Write-Host "Starting API (Spring profile: $Profile)..."
# Load .env variables (same behavior as `make dev`)
if (Test-Path .env) {
  Get-Content .env |
    Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } |
    ForEach-Object {
      $kv = $_ -split '=', 2
      [System.Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process')
    }
  Write-Host "Loaded .env"
}

$env:SPRING_PROFILES_ACTIVE = $Profile

# Use Maven wrapper for Windows.
./mvnw.cmd spring-boot:run
