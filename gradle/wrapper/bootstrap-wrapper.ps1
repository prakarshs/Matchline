# MATCHLINE Commit 01: first-use download of the genuine Gradle wrapper JAR.
$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot 'gradle-wrapper.jar'
$temporary = Join-Path $PSScriptRoot ("gradle-wrapper.jar.download.$PID")
$official = 'https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar'
$fallback = 'https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar'
$expectedSha256 = '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172'

if (Test-Path -LiteralPath $target) { exit 0 }
try {
    Write-Output '[MATCHLINE bootstrap] Fetching official Gradle 8.14.3 wrapper JAR...'
    try {
        Invoke-WebRequest -Uri $official -OutFile $temporary -UseBasicParsing
    } catch {
        Invoke-WebRequest -Uri $fallback -OutFile $temporary -UseBasicParsing
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
    if ($actual -ne $expectedSha256) {
        throw "Wrapper SHA-256 mismatch. Expected $expectedSha256, received $actual."
    }
    Move-Item -LiteralPath $temporary -Destination $target
    Write-Output '[MATCHLINE bootstrap] Official wrapper JAR verified and saved.'
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
}
