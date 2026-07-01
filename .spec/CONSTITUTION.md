# Constitution: open_oura Android Bridge (Local-First Sync)

## The Nine Articles of Development
The foundational rules that govern all code generation in this project:

- **Article I: Stateless & Bridge-Only**: The FFI boundary must remain entirely stateless, performing only pure cryptographic, encoding/decoding, and structural operations without managing connection lifecycle or persisting state.
- **Article II: Local-First & Zero-Telemetry**: No network requests, cloud telemetry, analytics SDKs, or remote logging. The app operates strictly in a local-only runtime.
- **Article III: Test-First & Pure Validation**: FFI decoders and JNA mappings must be unit tested with realistic bytes or mocks before integration.
- **Article IV: Memory Discipline**: Every raw pointer or heap-allocated memory boundary (such as JSON strings returned from Rust) must be explicitly managed, ensuring reliable freeing via `oura_string_free` in Kotlin `finally` blocks.
- **Article V: Thread Safety & Async Non-Blocking**: Native calls to BLE decryption or parsing must never block the main Android thread. Use background dispatchers or Kotlin coroutines.
- **Article VI: Platform Target Pinning**: Exclusively target `arm64-v8a` for Google Pixel 10 Pro (Android 16, API level 36).
- **Article VII: Structural Simplicity**: Minimize JNA interfaces and keep the project structured into:
  1. Rust FFI layer (`oura-ffi`)
  2. Compilation automation scripts
  3. Kotlin wrapper + Android service stub
- **Article VIII: Anti-Abstraction**: Directly expose standard JNA types and Android Bluetooth/WorkManager structures. Do not add complex lifecycle abstractions.
- **Article IX: Real-Byte Verification**: Tests must use real event frames captured from hardware or documented in tests (e.g. `green_ibi_quality_event` raw packet data).

## Technology Stack
- **Languages**: Rust (edition 2021), Kotlin (1.9+)
- **Build Systems**: Cargo + `cargo-ndk`, Gradle (Kotlin DSL)
- **Native Bridge**: Java Native Access (JNA) 5.12.0+ for Android
- **Min/Target SDK**: Android 16 (API 36) / Target SDK 36 (arm64-v8a)

## Project-Specific Rules
- **Rule 1**: The Rust crate `oura-ffi` must be a direct workspace member of the `open_oura` repository.
- **Rule 2**: `cargo ndk` command execution must target `arm64-v8a` exclusively to keep the automation simple and focused.
- **Rule 3**: `liboura_ffi.so` output must be dropped in the standard JNI location: `android/app/src/main/jniLibs/arm64-v8a/liboura_ffi.so`.
