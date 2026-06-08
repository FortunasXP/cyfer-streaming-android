# Download the libdovi-enabled mpv-android-lib AAR into app/libs/.
#
# Cyfer Streaming Android depends on a locally-rebuilt mpv-android-lib
# with libdovi statically linked (Dolby Vision Profile 5/8 RPU reshape).
# The AAR is too large (~155 MB) to commit to git, so this script pulls
# it on demand from the build pipeline repo's GitHub Releases.
#
# Run once after cloning:
#   cd android
#   .\scripts\fetch-mpv-libdovi.ps1
#
# The gradle build references it as `files("libs/mpv-android-lib-libdovi.aar")`.

[CmdletBinding()]
param(
    [string]$Tag = 'mpv-libdovi-v1',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot
$libsDir = Join-Path $projectRoot 'app\libs'
$dest = Join-Path $libsDir 'mpv-android-lib-libdovi.aar'

if ((Test-Path $dest) -and -not $Force) {
    $sizeMB = [math]::Round((Get-Item $dest).Length / 1MB, 1)
    Write-Host "AAR already present at $dest ($sizeMB MB) — use -Force to re-download." -ForegroundColor Green
    return
}

New-Item -ItemType Directory -Force -Path $libsDir | Out-Null

$url = "https://github.com/FortunasXP/mpv-android-libdovi/releases/download/$Tag/mpv-android-lib-libdovi.aar"
Write-Host "Downloading from $url" -ForegroundColor Cyan
Write-Host "Destination: $dest"
Write-Host "Size: ~155 MB. This will take a minute on most connections."

# Use System.Net.Http for streaming download (Invoke-WebRequest buffers
# the whole body into memory which is wasteful for a 155 MB asset).
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
try {
    $client.Timeout = [TimeSpan]::FromMinutes(15)
    $response = $client.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $response.EnsureSuccessStatusCode() | Out-Null
    $stream = $response.Content.ReadAsStreamAsync().Result
    $file = [System.IO.File]::OpenWrite($dest)
    try {
        $stream.CopyTo($file)
    } finally {
        $file.Dispose()
        $stream.Dispose()
    }
} finally {
    $client.Dispose()
}

$sizeMB = [math]::Round((Get-Item $dest).Length / 1MB, 1)
Write-Host "Done — $dest ($sizeMB MB)" -ForegroundColor Green
