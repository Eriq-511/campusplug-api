param(
  [switch]$CI
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
  $here = Split-Path -Parent $PSCommandPath
  return (Resolve-Path (Join-Path $here '..')).Path
}

$repoRoot = Resolve-RepoRoot
$checklistPath = Join-Path $repoRoot 'checklist.md'
$reflectorPath = Join-Path $repoRoot 'reflector.md'

if (-not (Test-Path $checklistPath)) {
  throw "Missing checklist.md at $checklistPath"
}
if (-not (Test-Path $reflectorPath)) {
  throw "Missing reflector.md at $reflectorPath"
}

$checklistLines = Get-Content -Path $checklistPath
$reflector = Get-Content -Raw -Path $reflectorPath

$phaseHeadingRegex = '^##\s+Phase\s+(?<num>\d+)\b(?<rest>.*)$'
$checkboxRegex = '^\s*-\s*\[(?<state>[ xX])\]\s*(?<text>.*)$'

$phases = [System.Collections.Generic.List[object]]::new()

for ($i = 0; $i -lt $checklistLines.Count; $i++) {
  $line = $checklistLines[$i]
  $m = [regex]::Match($line, $phaseHeadingRegex)
  if (-not $m.Success) {
    continue
  }

  $num = [int]$m.Groups['num'].Value
  $rest = $m.Groups['rest'].Value
  $name = ($rest -replace '^[\s\p{Pd}]+' , '').Trim()

  $total = 0
  $checked = 0

  for ($j = $i + 1; $j -lt $checklistLines.Count; $j++) {
    $nextLine = $checklistLines[$j]
    if ($nextLine -match '^##\s+Phase\s+\d+\b' -or $nextLine -match '^##\s+Final gate\b') {
      break
    }

    $cb = [regex]::Match($nextLine, $checkboxRegex)
    if (-not $cb.Success) {
      continue
    }

    $text = $cb.Groups['text'].Value
    if ($text -match '\(Optional\)') {
      continue
    }

    $total++
    if ($cb.Groups['state'].Value -match '[xX]') {
      $checked++
    }
  }

  $isComplete = ($total -gt 0 -and $checked -eq $total)
  $hasReflection = [regex]::IsMatch($reflector, "(?m)^### Phase\s+$num\b")

  $phases.Add([pscustomobject]@{
      Phase = $num
      Name = $name
      Checked = $checked
      Total = $total
      Complete = $isComplete
      Reflection = $hasReflection
    })
}

if ($phases.Count -eq 0) {
  Write-Host 'No phases found in checklist.md.'
  exit 0
}

Write-Host 'Phase gate summary (from checklist.md + reflector.md)'
$phases | Sort-Object Phase | Format-Table -AutoSize Phase, Name, Checked, Total, Complete, Reflection | Out-String | Write-Host

$missingReflections = $phases | Where-Object { $_.Complete -and -not $_.Reflection }
if ($missingReflections.Count -gt 0) {
  Write-Error 'Phase gate FAILED: one or more completed phases are missing a matching reflection entry in reflector.md.'
  foreach ($p in ($missingReflections | Sort-Object Phase)) {
    Write-Error ("Missing reflection for Phase {0} - {1}" -f $p.Phase, $p.Name)
  }
  exit 1
}

Write-Host 'Phase gate OK.'
exit 0
