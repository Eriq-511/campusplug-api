param(
  [string]$BaseUrl = 'http://localhost:8080',
  [string]$JarPath = 'target\\campusplug-api-0.0.1-SNAPSHOT.jar',
  [string]$Profile = 'local'
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$Message) {
  Write-Host ("\n==> {0}" -f $Message)
}

function Invoke-JsonPost([string]$Url, $Body, $Headers = $null) {
  $json = $Body | ConvertTo-Json -Depth 10
  if ($Headers) {
    return Invoke-RestMethod -Method Post -Uri $Url -ContentType 'application/json' -Body $json -Headers $Headers
  }
  return Invoke-RestMethod -Method Post -Uri $Url -ContentType 'application/json' -Body $json
}

function Get-HttpStatusFromException($Exception) {
  try {
    if ($Exception.Response) {
      return [int]$Exception.Response.StatusCode
    }
  } catch {
  }
  return $null
}

$env:SPRING_PROFILES_ACTIVE = $Profile

Write-Step "Starting API from JAR ($JarPath)"
$proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $JarPath) -WorkingDirectory (Get-Location) -PassThru

try {
  Write-Step 'Waiting for /actuator/health'
  $health = $null
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health"
      break
    } catch {
      Start-Sleep -Seconds 1
    }
  }
  if (-not $health) {
    throw 'Server did not become healthy within 60 seconds.'
  }

  Write-Step 'GET /actuator/health'
  $health | ConvertTo-Json -Depth 10 | Write-Host

  Write-Step 'GET /'
  (Invoke-RestMethod -Method Get -Uri "$BaseUrl/") | Out-String | Write-Host

  $email = "test.user+$(Get-Random -Minimum 1000 -Maximum 9999)@must.ac.ug"
  $password = 'Password123!'

  Write-Step "POST /api/v1/auth/register ($email)"
  $register = Invoke-JsonPost "$BaseUrl/api/v1/auth/register" @{
    fullName = 'Test User'
    registrationNumber = '2023/CS/001'
    email = $email
    phoneNumber = '+256700000001'
    password = $password
    confirmPassword = $password
  }
  $register | ConvertTo-Json -Depth 10 | Write-Host

  Write-Step 'POST /api/v1/auth/login'
  $login = Invoke-JsonPost "$BaseUrl/api/v1/auth/login" @{ email = $email; password = $password }
  $login | ConvertTo-Json -Depth 10 | Write-Host
  $token = $login.token
  if (-not $token) {
    throw 'Login did not return a token.'
  }

  Write-Step 'GET /actuator/info (no auth) - expect 401'
  try {
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/info" | Out-String | Write-Host
    throw 'Expected 401 for /actuator/info without auth, but request succeeded.'
  } catch {
    $status = Get-HttpStatusFromException $_.Exception
    if ($status -ne 401) {
      throw "Expected 401 for /actuator/info without auth, got $status"
    }
    Write-Host "OK: got HTTP $status"
  }

  Write-Step 'GET /actuator/info (with bearer)'
  $headers = @{ Authorization = "Bearer $token" }
  $info = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/info" -Headers $headers
  $info | ConvertTo-Json -Depth 10 | Write-Host

  Write-Step 'POST /api/v1/auth/logout'
  $logout = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/logout" -Headers $headers
  $logout | ConvertTo-Json -Depth 10 | Write-Host

  Write-Step 'GET /actuator/info (old bearer after logout) - expect 401'
  try {
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/info" -Headers $headers | Out-String | Write-Host
    throw 'Expected 401 after logout, but request succeeded.'
  } catch {
    $status = Get-HttpStatusFromException $_.Exception
    if ($status -ne 401) {
      throw "Expected 401 after logout, got $status"
    }
    Write-Host "OK: got HTTP $status"
  }

  Write-Step 'POST /api/v1/auth/forgot-password'
  $forgot = Invoke-JsonPost "$BaseUrl/api/v1/auth/forgot-password" @{ email = $email }
  $forgot | ConvertTo-Json -Depth 10 | Write-Host

  $resetToken = $forgot.resetToken
  if (-not $resetToken) {
    throw 'Expected resetToken in non-prod forgot-password response but it was missing.'
  }

  Write-Step 'POST /api/v1/auth/reset-password'
  $newPassword = 'Password456!'
  $reset = Invoke-JsonPost "$BaseUrl/api/v1/auth/reset-password" @{ token = $resetToken; password = $newPassword; confirmPassword = $newPassword }
  $reset | ConvertTo-Json -Depth 10 | Write-Host

  Write-Step 'POST /api/v1/auth/login (new password)'
  $login2 = Invoke-JsonPost "$BaseUrl/api/v1/auth/login" @{ email = $email; password = $newPassword }
  $login2 | ConvertTo-Json -Depth 10 | Write-Host

  Write-Host "\nSmoke tests PASSED."
}
finally {
  if ($proc -and -not $proc.HasExited) {
    Write-Step "Stopping API PID $($proc.Id)"
    Stop-Process -Id $proc.Id -Force
  }
}
