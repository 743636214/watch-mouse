# Android Studio C Drive to D Drive Migration Script
# Usage: Run as Administrator in PowerShell
#
# Steps:
#   1. Close Android Studio
#   2. Right-click Start menu -> "Windows PowerShell (Admin)"
#   3. Run: powershell -ExecutionPolicy Bypass -File "E:\codes\watch-mouse\migrate-android-to-d.ps1"

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Android Studio C->D Drive Migration" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Check if running as Administrator
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[ERROR] Please run this script as Administrator!" -ForegroundColor Red
    Write-Host "Right-click Start menu -> 'Windows PowerShell (Admin)'" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Check if Android Studio is running
$studioProcess = Get-Process -Name "studio64","studio" -ErrorAction SilentlyContinue
if ($studioProcess) {
    Write-Host "[ERROR] Android Studio is running. Please close it first!" -ForegroundColor Red
    $studioProcess | Select-Object Name, Id | Format-Table
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "[OK] Administrator check passed" -ForegroundColor Green
Write-Host "[OK] Android Studio is closed" -ForegroundColor Green
Write-Host ""

# Define migration items
$migrations = @(
    @{
        Name = "Android SDK"
        Source = "C:\Users\Administrator\AppData\Local\Android\Sdk"
        Target = "D:\Android\Sdk"
        Size = "~4.26 GB"
    },
    @{
        Name = "Android Studio Cache"
        Source = "C:\Users\Administrator\AppData\Local\Google"
        Target = "D:\AndroidStudioCache\Google"
        Size = "~2.04 GB"
    },
    @{
        Name = "AVD Emulator"
        Source = "C:\Users\Administrator\.android"
        Target = "D:\Android\.android"
        Size = "~0.50 GB"
    }
)

# Show migration plan
Write-Host "Migration Plan:" -ForegroundColor Yellow
Write-Host ""
for ($i = 0; $i -lt $migrations.Count; $i++) {
    $m = $migrations[$i]
    $num = $i + 1
    Write-Host "  $num. $($m.Name) ($($m.Size))" -ForegroundColor White
    Write-Host "     $($m.Source)" -ForegroundColor DarkGray
    Write-Host "     -> $($m.Target)" -ForegroundColor DarkGray
    Write-Host ""
}
Write-Host "Total C drive space to free: ~6.8 GB" -ForegroundColor Green
Write-Host ""

# Confirm
$confirm = Read-Host "Start migration? (y/N)"
if ($confirm -ne "y" -and $confirm -ne "Y") {
    Write-Host "Migration cancelled." -ForegroundColor Yellow
    exit 0
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

$successCount = 0
$failCount = 0

foreach ($m in $migrations) {
    $name = $m.Name
    $source = $m.Source
    $target = $m.Target

    Write-Host "[$($m.Name)] Starting..." -ForegroundColor Cyan

    # Check if source exists
    if (-not (Test-Path $source)) {
        Write-Host "  [SKIP] Source not found: $source" -ForegroundColor Yellow
        Write-Host ""
        continue
    }

    # Check if source is already a Junction (already migrated)
    $item = Get-Item $source -ErrorAction SilentlyContinue
    if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        Write-Host "  [SKIP] Already a symlink, no need to migrate" -ForegroundColor Yellow
        Write-Host ""
        continue
    }

    # Create target parent directory
    $targetParent = Split-Path $target -Parent
    if (-not (Test-Path $targetParent)) {
        New-Item -ItemType Directory -Force -Path $targetParent | Out-Null
    }

    # If target exists and non-empty, skip
    if (Test-Path $target) {
        $targetEmpty = (Get-ChildItem $target -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0
        if (-not $targetEmpty) {
            Write-Host "  [SKIP] Target not empty, please handle manually" -ForegroundColor Yellow
            Write-Host ""
            continue
        }
        Remove-Item $target -Force -ErrorAction SilentlyContinue
    }

    # Use robocopy to copy (most reliable)
    Write-Host "  Copying data, please wait..." -ForegroundColor DarkGray
    $tempTarget = $target + "_temp_copy"
    if (Test-Path $tempTarget) {
        Remove-Item $tempTarget -Recurse -Force -ErrorAction SilentlyContinue
    }

    # Copy first (don't delete source yet)
    $null = robocopy $source $tempTarget /E /R:2 /W:2 /MT:8 /NFL /NDL /NJH /NJS

    if ($LASTEXITCODE -ge 8) {
        Write-Host "  [FAIL] robocopy failed, exit code: $LASTEXITCODE" -ForegroundColor Red
        $failCount++
        Write-Host ""
        continue
    }

    # Rename temp dir to target
    Rename-Item -Path $tempTarget -NewName (Split-Path $target -Leaf) -Force

    # Delete source directory
    Write-Host "  Deleting original C drive data..." -ForegroundColor DarkGray
    Remove-Item -Path $source -Recurse -Force -ErrorAction SilentlyContinue

    # Check if source was deleted
    if (Test-Path $source) {
        try {
            Remove-Item -Path $source -Recurse -Force -ErrorAction Stop
        } catch {
            Write-Host "  [WARN] Failed to delete source, please delete manually: $source" -ForegroundColor Yellow
        }
    }

    # Create Junction (symbolic link)
    Write-Host "  Creating junction link..." -ForegroundColor DarkGray
    if (Test-Path $source) {
        Remove-Item $source -Recurse -Force -ErrorAction SilentlyContinue
    }

    # Use mklink /J to create Junction
    $mklinkResult = & "$env:COMSPEC" /c "mklink /J `"$source`" `"$target`""

    if (Test-Path $source) {
        Write-Host "  [DONE] Migration successful!" -ForegroundColor Green
        Write-Host "    $source -> $target" -ForegroundColor DarkGray
        $successCount++
    } else {
        Write-Host "  [FAIL] Junction creation failed" -ForegroundColor Red
        $failCount++
    }

    Write-Host ""
}

# Summary
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Migration Complete" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Success: $successCount item(s)" -ForegroundColor Green
Write-Host "  Failed:  $failCount item(s)" -ForegroundColor $(if ($failCount -gt 0) { "Red" } else { "Gray" })
Write-Host ""

# Show disk space
Write-Host "C drive free space:" -ForegroundColor Yellow
$drive = Get-PSDrive C
Write-Host "  $([math]::Round($drive.Free / 1GB, 2)) GB" -ForegroundColor White
Write-Host ""

Write-Host "D drive free space:" -ForegroundColor Yellow
$driveD = Get-PSDrive D
Write-Host "  $([math]::Round($driveD.Free / 1GB, 2)) GB" -ForegroundColor White
Write-Host ""

Write-Host "You can now reopen Android Studio." -ForegroundColor Green
Write-Host ""
Read-Host "Press Enter to exit"
