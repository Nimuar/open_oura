# PowerShell Script to Cross-Compile oura-ffi for Android (arm64-v8a)

$ErrorActionPreference = "Stop"

# Ensure cargo-ndk is installed
Write-Host "Checking for cargo-ndk..."
if (-not (Get-Command "cargo-ndk" -ErrorAction SilentlyContinue)) {
    Write-Error "cargo-ndk is not installed. Please run: cargo install cargo-ndk"
}

# Define paths
$ProjectRoot = "C:\Users\kevin\Documents\Github\open_oura"
$JniLibsDir = Join-Path $ProjectRoot "android\app\src\main\jniLibs\arm64-v8a"
$RustBuildArtifact = Join-Path $ProjectRoot "target\aarch64-linux-android\release\liboura_ffi.so"

Write-Host "Building oura-ffi for arm64-v8a..."
# Run cargo ndk build. Target Android API 34 (Android 14) or 35 (Android 15).
cargo ndk -t arm64-v8a -P 34 build --package oura-ffi --release

if (-not (Test-Path $JniLibsDir)) {
    Write-Host "Creating JNI libs directory: $JniLibsDir"
    New-Item -ItemType Directory -Force -Path $JniLibsDir | Out-Null
}

Write-Host "Copying compiled binary to Android project..."
Copy-Item -Path $RustBuildArtifact -Destination $JniLibsDir -Force

Write-Host "Successfully compiled and copied liboura_ffi.so to $JniLibsDir"
