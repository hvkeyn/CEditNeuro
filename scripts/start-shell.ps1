# Starts the installed Shizuku server from the computer's existing adb link.
# The Shizuku screen stays closed. The server runs as the shell user, not root.
$ErrorActionPreference = "Stop"

function Get-DeviceSerial {
    $lines = adb devices | Select-String "\tdevice$"
    if (-not $lines) { throw "No adb device is connected." }
    $serials = foreach ($line in $lines) { ($line.ToString() -split "\s+")[0] }
    $wireless = $serials | Where-Object { $_ -like "*adb-tls-connect*" } | Select-Object -First 1
    if ($wireless) { return $wireless }
    return $serials | Select-Object -First 1
}

$serial = Get-DeviceSerial
$apkLine = (adb -s $serial shell "pm path moe.shizuku.privileged.api").Trim()
if ($apkLine -notlike "package:*") {
    throw "Shizuku is not installed. Install it once, then run this script again."
}
$remoteApk = $apkLine.Substring("package:".Length)
$work = Join-Path $env:TEMP "ceditneuro-shell"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$localApk = Join-Path $work "base.apk"
cmd /c "adb -s `"$serial`" pull `"$remoteApk`" `"$localApk`" >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw "Could not read the installed Shizuku package." }

$abi = (adb -s $serial shell getprop ro.product.cpu.abi).Trim()
$entry = switch ($abi) {
    "armeabi-v7a" { "lib/armeabi-v7a/libshizuku.so" }
    "x86_64" { "lib/x86_64/libshizuku.so" }
    "x86" { "lib/x86/libshizuku.so" }
    default { "lib/arm64-v8a/libshizuku.so" }
}
$libDir = Join-Path $work "lib"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($localApk)
try {
    $item = $zip.Entries | Where-Object { $_.FullName -eq $entry } | Select-Object -First 1
    if (-not $item) { throw "The Shizuku package has no starter for $abi." }
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    $localLib = Join-Path $libDir "libshizuku.so"
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($item, $localLib, $true)
} finally {
    $zip.Dispose()
}

cmd /c "adb -s `"$serial`" push `"$localLib`" /data/local/tmp/libshizuku.so >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw "Could not copy the Shizuku starter to the phone." }
$log = cmd /c "adb -s `"$serial`" shell `"chmod 755 /data/local/tmp/libshizuku.so; /data/local/tmp/libshizuku.so`" 2>&1" | Out-String
if ($log -notmatch "shizuku_starter exit with 0") {
    throw "The Shizuku server did not start."
}
Start-Sleep -Seconds 3
Write-Output "Shizuku server is running. The Shizuku screen was not opened."
