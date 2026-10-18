$ErrorActionPreference = "Stop"
$norwixVersion = "8.11.1"
$norwixRoot = Split-Path -Parent $PSScriptRoot
$norwixCache = if ($env:NORWIX_BUILD_CACHE) { $env:NORWIX_BUILD_CACHE } else { Join-Path $env:LOCALAPPDATA "NorwixBuildTools" }
$norwixGradle = Join-Path $norwixCache "gradle-$norwixVersion\bin\gradle.bat"
if (-not (Test-Path $norwixGradle)) {
    New-Item -ItemType Directory -Force -Path $norwixCache | Out-Null
    $norwixTemp = Join-Path $norwixCache ([Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $norwixTemp | Out-Null
    try {
        $norwixUrl = "https://services.gradle.org/distributions/gradle-$norwixVersion-bin.zip"
        $norwixZip = Join-Path $norwixTemp "gradle.zip"
        Write-Host "Downloading official Gradle $norwixVersion and verifying its SHA-256 checksum."
        Invoke-WebRequest -UseBasicParsing -Uri $norwixUrl -OutFile $norwixZip
        $norwixExpected = (Invoke-WebRequest -UseBasicParsing -Uri "$norwixUrl.sha256").Content.Trim()
        $norwixActual = (Get-FileHash -Algorithm SHA256 -Path $norwixZip).Hash.ToLowerInvariant()
        if ($norwixExpected -notmatch '^[a-fA-F0-9]{64}$' -or $norwixActual -ne $norwixExpected.ToLowerInvariant()) {
            throw "Gradle checksum verification failed. Nothing was executed."
        }
        Expand-Archive -Path $norwixZip -DestinationPath $norwixTemp
        Move-Item -Path (Join-Path $norwixTemp "gradle-$norwixVersion") -Destination $norwixCache
    } finally { Remove-Item -Recurse -Force -Path $norwixTemp }
}
Push-Location $norwixRoot
try { & $norwixGradle @args; exit $LASTEXITCODE } finally { Pop-Location }
