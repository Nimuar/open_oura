#!/bin/bash
set -e

# Ensure cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "Error: cargo-ndk is not installed. Please run: cargo install cargo-ndk"
    exit 1
fi

PROJECT_ROOT="$(pwd)"
JNI_LIBS_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs/arm64-v8a"
RUST_BUILD_ARTIFACT="$PROJECT_ROOT/target/aarch64-linux-android/release/liboura_ffi.so"

echo "Building oura-ffi for arm64-v8a..."
cargo ndk --target aarch64-linux-android --android-platform 35 build --package oura-ffi --release

if [ ! -d "$JNI_LIBS_DIR" ]; then
    echo "Creating JNI libs directory: $JNI_LIBS_DIR"
    mkdir -p "$JNI_LIBS_DIR"
fi

echo "Copying compiled binary to Android project..."
cp "$RUST_BUILD_ARTIFACT" "$JNI_LIBS_DIR/liboura_ffi.so"

echo "Successfully compiled and copied liboura_ffi.so to $JNI_LIBS_DIR"
