cmake_minimum_required(VERSION 3.22.1)
project(oura_ffi_bridge)

message(STATUS "Android ABI: ${ANDROID_ABI}")
message(STATUS "Android Platform Level: ${ANDROID_PLATFORM_LEVEL}")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "arm64-v8a")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}. This project only targets arm64-v8a.")
endif()

set(RUST_PROJECT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../..")
set(JNI_LIBS_OUT "${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs")

# Custom target that builds oura-ffi via cargo-ndk and puts it in src/main/jniLibs/<abi>
add_custom_target(build_rust_core ALL
    COMMAND cargo ndk -t ${RUST_TARGET} -P ${ANDROID_PLATFORM_LEVEL} -o "${JNI_LIBS_OUT}" build --package oura-ffi --release
    WORKING_DIRECTORY "${RUST_PROJECT_DIR}"
    COMMENT "Building Rust FFI via cargo-ndk and outputting to jniLibs"
)
