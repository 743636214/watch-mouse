# Fix: Migrate only AndroidStudio cache (not Chrome data)
# The Google folder contains both AndroidStudio cache and Chrome data
# We only move the AndroidStudio subfolder

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Fix: AndroidStudio Cache Migration" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Check admin
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[ERROR] Please run as Administrator!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Check Android Studio is closed
$studioProcess = Get-Process -Name "studio64","studio" -ErrorAction SilentlyContinue
if ($studioProcess) {
    Write-Host "[ERROR] Android Studio is running. Close it first!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

$source = "C:\Users\Administrator\AppData\Local\Google\AndroidStudio2026.2.1"
$target = "D:\AndroidStudioCache\Google\AndroidStudio2026.2.1"

Write-Host "Source: $source" -ForegroundColor Gray
Write-Host "Target: $target" -ForegroundColor Gray
Write-Host ""

# Check source exists
if (-not (Test-Path $source)) {
    Write-Host "[ERROR] Source not found!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Check if already a junction
$item = Get-Item $source
if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
    Write-Host "[SKIP] Already a symlink. Nothing to do." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 0
}

# Create target parent
$targetParent = Split-Path $target -Parent
if (-not (Test-Path $targetParent)) {
    New-Item -ItemType Directory -Force -Path $targetParent | Out-Null
}

# If target exists, remove it
if (Test-Path $target) {
    Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue
}

# Copy using robocopy
Write-Host "Copying AndroidStudio cache to D drive..." -ForegroundColor Cyan
$null = robocopy $source $target /E /R:2 /W:2 /MT:8 /NFL /NDL /NJH /NJS

if ($LASTEXITCODE -ge 8) {
    Write-Host "[FAIL] robocopy failed, exit code: $LASTEXITCODE" -ForegroundColor Red
    Write-Host "Try closing all Google/Chrome processes and retry." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "[OK] Copy complete" -ForegroundColor Green

# Delete source
Write-Host "Deleting original..." -ForegroundColor Cyan
Remove-Item -Path $source -Recurse -Force -ErrorAction SilentlyContinue

if (Test-Path $source) {
    try {
        Remove-Item -Path $source -Recurse -Force -ErrorAction Stop
    } catch {
        Write-Host "[WARN] Could not delete source: $source" -ForegroundColor Yellow
        Write-Host "You can delete it manually after." -ForegroundColor Yellow
    }
}

# Create junction
Write-Host "Creating junction..." -ForegroundColor Cyan
if (Test-Path $source) {
    Remove-Item $source -Recurse -Force -ErrorAction SilentlyContinue
}

& "$env:COMSPEC" /c "mklink /J `"$source`" `"$target`""

if (Test-Path $source) {
    Write-Host "[DONE] Migration successful!" -ForegroundColor Green
    Write-Host "  $source -> $target" -ForegroundColor Gray
} else {
    Write-Host "[FAIL] Junction creation failed" -ForegroundColor Red
}

Write-Host ""
Write-Host "C drive free:" -ForegroundColor Yellow
$drive = Get-PSDrive C
Write-Host "  $([math]::Round($drive.Free / 1GB, 2)) GB" -ForegroundColor White

Write-Host ""
Read-Host "Press Enter to exit"
