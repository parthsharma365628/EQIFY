<#
.SYNOPSIS
  One command for EQify builds on Windows.

.DESCRIPTION
  Sets JAVA_HOME and the unix-socket temp-path workaround Gradle needs on this
  machine (the user temp path contains a space), then runs the chosen task.

    test     JVM unit tests, with a pass/fail count
    debug    Debug APKs for the phone and the watch
    release  Tests, then signed release APKs for both modules
    verify   Check both release APKs share app ID, version and signing key,
             and copy them to EQify-<version>.apk and EQify-Wear-<version>.apk
    all      release, then verify

  Overrides: EQIFY_JAVA_HOME (default D:\ANDROID\jbr), EQIFY_SOCKET_DIR (default
  D:\ANDROID\gradle-sock), ANDROID_SDK (default from local.properties).

.EXAMPLE
  .\scripts\build.ps1 test
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('test', 'debug', 'release', 'verify', 'all')]
    [string]$Task = 'test'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo

# ── Environment ─────────────────────────────────────────────────────────
$javaHome = if ($env:EQIFY_JAVA_HOME) { $env:EQIFY_JAVA_HOME } else { 'D:\ANDROID\jbr' }
if (-not (Test-Path -LiteralPath "$javaHome\bin\java.exe")) { throw "No JDK at $javaHome. Set EQIFY_JAVA_HOME." }
$env:JAVA_HOME = $javaHome

# Gradle's loopback socket fails under the user temp folder on this machine (its path has a
# space, and even its 8.3 short form fails), so use a folder next to the JDK instead.
$socketDir = if ($env:EQIFY_SOCKET_DIR) { $env:EQIFY_SOCKET_DIR } else { Join-Path (Split-Path -Parent $javaHome) 'gradle-sock' }
if ($socketDir -match ' ') { throw "The socket folder must not contain a space: $socketDir. Set EQIFY_SOCKET_DIR." }
New-Item -ItemType Directory -Force -Path $socketDir | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$socketDir"

function Invoke-Gradle([string[]]$tasks) {
    Write-Host "> gradlew $($tasks -join ' ')" -ForegroundColor Cyan
    & cmd /c ".\gradlew.bat $($tasks -join ' ') --console=plain -q 2>&1" |
        Where-Object { $_ -notmatch '^Picked up JAVA_TOOL_OPTIONS' } | Write-Host
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)." }
}

function Show-TestCount {
    $total = 0; $failed = 0
    Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
        $suite = ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite
        $total += [int]$suite.tests; $failed += [int]$suite.failures + [int]$suite.errors
    }
    $color = if ($failed -eq 0) { 'Green' } else { 'Red' }
    Write-Host "Tests: $total run, $failed failed" -ForegroundColor $color
    if ($failed -gt 0) { throw "$failed test(s) failed." }
}

function Get-BuildTools {
    $sdk = $env:ANDROID_SDK
    if (-not $sdk -and (Test-Path 'local.properties')) {
        $line = Select-String -Path 'local.properties' -Pattern '^sdk\.dir=' | Select-Object -First 1
        if ($line) { $sdk = ($line.Line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
    }
    if (-not $sdk) { throw 'Android SDK not found. Set ANDROID_SDK.' }
    $tools = Get-ChildItem -LiteralPath "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $tools) { throw "No build-tools in $sdk." }
    $tools.FullName
}

function Test-ReleaseApks {
    $bt = Get-BuildTools
    $env:JAVA_TOOL_OPTIONS = $null      # only Gradle needs it; apksigner would print a notice
    $apks = @(
        @{ Name = 'phone'; Path = 'app\build\outputs\apk\release\app-release.apk'; Prefix = 'EQify' },
        @{ Name = 'watch'; Path = 'wear\build\outputs\apk\release\wear-release.apk'; Prefix = 'EQify-Wear' }
    )
    $seen = @()
    foreach ($a in $apks) {
        if (-not (Test-Path -LiteralPath $a.Path)) { throw "Missing $($a.Path). Run: .\scripts\build.ps1 release" }
        $badging = & "$bt\aapt2.exe" dump badging $a.Path | Select-Object -First 1
        if ($badging -notmatch "name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { throw "Couldn't read $($a.Path)." }
        $id, $code, $name = $Matches[1], $Matches[2], $Matches[3]
        $cert = (& "$bt\apksigner.bat" verify --print-certs $a.Path | Select-String 'SHA-256 digest:' | Select-Object -First 1)
        if (-not $cert) { throw "$($a.Name) APK isn't signed. Check the EQIFY_KEYSTORE_* properties." }
        $digest = ($cert.Line -split ':\s*')[-1].Trim()
        Write-Host ("{0,-6} {1}  v{2} ({3})  key {4}..." -f $a.Name, $id, $name, $code, $digest.Substring(0, 12))
        $seen += [pscustomobject]@{ Id = $id; Code = $code; Name = $name; Digest = $digest; Path = $a.Path; Prefix = $a.Prefix }
    }
    if (($seen.Id | Select-Object -Unique).Count -ne 1) { throw 'The phone and watch app IDs differ.' }
    if (($seen.Digest | Select-Object -Unique).Count -ne 1) { throw 'The phone and watch APKs use different signing keys.' }
    if (($seen.Name | Select-Object -Unique).Count -ne 1) {
        Write-Host 'Warning: phone and watch versions differ (fine only if the watch app did not change).' -ForegroundColor Yellow
    }
    foreach ($s in $seen) {
        $dest = Join-Path (Split-Path -Parent $s.Path) "$($s.Prefix)-$($s.Name).apk"
        Copy-Item -LiteralPath $s.Path -Destination $dest -Force
        Write-Host "Ready: $dest" -ForegroundColor Green
    }
}

switch ($Task) {
    'test'    { Invoke-Gradle @(':app:testDebugUnitTest'); Show-TestCount }
    'debug'   { Invoke-Gradle @('assembleDebug') }
    'release' { Invoke-Gradle @(':app:testDebugUnitTest'); Show-TestCount; Invoke-Gradle @('assembleRelease') }
    'verify'  { Test-ReleaseApks }
    'all'     { Invoke-Gradle @(':app:testDebugUnitTest'); Show-TestCount; Invoke-Gradle @('assembleRelease'); Test-ReleaseApks }
}
