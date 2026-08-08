//! C ABI over the tested Oura protocol core, for linking into the Android/iOS apps.
//!
//! We expose only the byte-level pieces that are genuinely hard to reproduce —
//! the AES auth and the event-body decoders — as pure, synchronous functions.
//! BLE transport, packet framing (trivial `tag|len|payload`), the request
//! builders (tiny byte arrays), and the connect/sync orchestration are all done
//! natively in Kotlin/Swift, so nothing async crosses the FFI boundary.

use std::ffi::{c_char, CString};
use std::slice;
use std::collections::VecDeque;
use std::sync::Mutex;
use once_cell::sync::Lazy;

use oura_protocol::auth::encrypt_nonce;
use oura_protocol::events::{decode_event_body, event_name};

static DIAGNOSTIC_LOGS: Lazy<Mutex<VecDeque<String>>> = Lazy::new(|| {
    Mutex::new(VecDeque::with_capacity(500))
});

fn log_diagnostic(msg: &str) {
    if let Ok(mut logs) = DIAGNOSTIC_LOGS.lock() {
        if logs.len() >= 500 {
            logs.pop_front();
        }
        logs.push_back(msg.to_string());
    }
}

/// Pop the oldest diagnostic log message from the Rust-native diagnostics queue.
/// Returns a null-terminated C-string representing the log message, or null if the queue
/// is empty. The returned string is owned by the caller and must be released with [`oura_string_free`].
#[no_mangle]
pub extern "C" fn oura_pop_diagnostic() -> *mut c_char {
    if let Ok(mut logs) = DIAGNOSTIC_LOGS.lock() {
        if let Some(log) = logs.pop_front() {
            return CString::new(log)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    }
    std::ptr::null_mut()
}

/// Encrypt a ring auth nonce (AES-128/ECB/PKCS7) into `out` (must hold 16 bytes).
/// `key` must be exactly 16 bytes; `nonce` is typically 15. Returns 0 on success,
/// negative on bad arguments.
#[no_mangle]
pub extern "C" fn oura_encrypt_nonce(
    key: *const u8,
    key_len: usize,
    nonce: *const u8,
    nonce_len: usize,
    out: *mut u8,
) -> i32 {
    log_diagnostic("FFI: oura_encrypt_nonce invoked");
    if key.is_null() || nonce.is_null() || out.is_null() || key_len != 16 {
        log_diagnostic("FFI error: oura_encrypt_nonce invalid arguments");
        return -1;
    }
    // SAFETY: caller guarantees the pointers are valid for the given lengths.
    let key_slice = unsafe { slice::from_raw_parts(key, 16) };
    let nonce_slice = unsafe { slice::from_raw_parts(nonce, nonce_len) };
    let mut k = [0u8; 16];
    k.copy_from_slice(key_slice);
    let res = encrypt_nonce(&k, nonce_slice);
    unsafe { std::ptr::copy_nonoverlapping(res.as_ptr(), out, 16) };
    log_diagnostic("FFI: oura_encrypt_nonce completed successfully");
    0
}

/// Decode an event body for `tag` into a JSON C string, or null if the tag has no
/// decoder / the body is malformed. The returned string is owned by the caller and
/// must be released with [`oura_string_free`].
#[no_mangle]
pub extern "C" fn oura_decode_event(tag: u8, body: *const u8, body_len: usize) -> *mut c_char {
    log_diagnostic(&format!("FFI: oura_decode_event starting for tag 0x{:02x}, len {}", tag, body_len));
    let body: &[u8] = if body.is_null() || body_len == 0 {
        &[]
    } else {
        // SAFETY: caller guarantees `body` is valid for `body_len` bytes.
        unsafe { slice::from_raw_parts(body, body_len) }
    };
    match decode_event_body(tag, body) {
        Some(v) => {
            log_diagnostic(&format!("FFI: oura_decode_event success for tag 0x{:02x}", tag));
            CString::new(v.to_string())
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
        None => {
            log_diagnostic(&format!("FFI error: oura_decode_event failed or unsupported for tag 0x{:02x}", tag));
            std::ptr::null_mut()
        }
    }
}

/// Human-readable event name for `tag` (owned C string; release with
/// [`oura_string_free`]).
#[no_mangle]
pub extern "C" fn oura_event_name(tag: u8) -> *mut c_char {
    log_diagnostic(&format!("FFI: oura_event_name for tag 0x{:02x}", tag));
    CString::new(event_name(tag))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Release a C string previously returned by this library.
#[no_mangle]
pub extern "C" fn oura_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        // SAFETY: `ptr` came from `CString::into_raw` in this library.
        unsafe { drop(CString::from_raw(ptr)) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;

    /// The diagnostics queue is process-global and every FFI entry point writes to
    /// it, so tests that inspect it must not run alongside tests that log.
    static DIAGNOSTICS: Mutex<()> = Mutex::new(());

    #[test]
    fn test_event_name() {
        let _guard = DIAGNOSTICS.lock();
        let name_ptr = oura_event_name(0x42);
        assert!(!name_ptr.is_null());
        let c_str = unsafe { CStr::from_ptr(name_ptr) };
        assert_eq!(c_str.to_str().unwrap(), "time_sync");
        oura_string_free(name_ptr);
    }

    #[test]
    fn test_decode_event_time_sync() {
        let _guard = DIAGNOSTICS.lock();
        // time_sync payload is 4-byte LE unix timestamp.
        let body = [0x01, 0x02, 0x03, 0x04];
        let json_ptr = oura_decode_event(0x42, body.as_ptr(), body.len());
        assert!(!json_ptr.is_null());
        let c_str = unsafe { CStr::from_ptr(json_ptr) };
        let json_str = c_str.to_str().unwrap();
        // serde_json serialization might vary slightly in ordering but for {"unix_time":67305985} it is straightforward.
        assert_eq!(json_str, "{\"unix_time\":67305985}");
        oura_string_free(json_ptr);
    }

    #[test]
    fn test_encrypt_nonce() {
        let _guard = DIAGNOSTICS.lock();
        let key = [0u8; 16];
        let nonce = [1u8; 15];
        let mut out = [0u8; 16];
        let res = oura_encrypt_nonce(key.as_ptr(), key.len(), nonce.as_ptr(), nonce.len(), out.as_mut_ptr());
        assert_eq!(res, 0);
        // Verify we got non-zero bytes (encryption did something)
        assert_ne!(out, [0u8; 16]);
    }

    #[test]
    fn test_diagnostics_queue() {
        let _guard = DIAGNOSTICS.lock();
        // Clear queue first by popping everything
        while !oura_pop_diagnostic().is_null() {}

        log_diagnostic("test log 1");
        log_diagnostic("test log 2");

        let log_ptr1 = oura_pop_diagnostic();
        assert!(!log_ptr1.is_null());
        let c_str1 = unsafe { CStr::from_ptr(log_ptr1) };
        assert_eq!(c_str1.to_str().unwrap(), "test log 1");
        oura_string_free(log_ptr1);

        let log_ptr2 = oura_pop_diagnostic();
        assert!(!log_ptr2.is_null());
        let c_str2 = unsafe { CStr::from_ptr(log_ptr2) };
        assert_eq!(c_str2.to_str().unwrap(), "test log 2");
        oura_string_free(log_ptr2);

        let empty_ptr = oura_pop_diagnostic();
        assert!(empty_ptr.is_null());
    }

    #[test]
    fn diagnostics_queue_drops_oldest_beyond_capacity() {
        let _guard = DIAGNOSTICS.lock();
        while !oura_pop_diagnostic().is_null() {}

        for i in 0..505 {
            log_diagnostic(&format!("log {i}"));
        }
        let first = oura_pop_diagnostic();
        let text = unsafe { CStr::from_ptr(first) }.to_str().unwrap().to_string();
        oura_string_free(first);
        // The queue holds the newest 500 entries, so 0-4 have been dropped.
        assert_eq!(text, "log 5");

        let mut remaining = 1;
        loop {
            let ptr = oura_pop_diagnostic();
            if ptr.is_null() {
                break;
            }
            oura_string_free(ptr);
            remaining += 1;
        }
        assert_eq!(remaining, 500);
    }

    #[test]
    fn encrypt_nonce_rejects_bad_arguments() {
        let _guard = DIAGNOSTICS.lock();
        let key = [0u8; 16];
        let nonce = [1u8; 15];
        let mut out = [0u8; 16];
        // wrong key length
        assert_eq!(
            oura_encrypt_nonce(key.as_ptr(), 8, nonce.as_ptr(), nonce.len(), out.as_mut_ptr()),
            -1
        );
        // null pointers
        assert_eq!(
            oura_encrypt_nonce(std::ptr::null(), 16, nonce.as_ptr(), nonce.len(), out.as_mut_ptr()),
            -1
        );
        assert_eq!(
            oura_encrypt_nonce(key.as_ptr(), 16, std::ptr::null(), 15, out.as_mut_ptr()),
            -1
        );
        assert_eq!(
            oura_encrypt_nonce(key.as_ptr(), 16, nonce.as_ptr(), nonce.len(), std::ptr::null_mut()),
            -1
        );
    }

    #[test]
    fn decode_event_returns_null_when_undecodable() {
        let _guard = DIAGNOSTICS.lock();
        // no decoder for this tag
        assert!(oura_decode_event(0x44, [1u8, 2].as_ptr(), 2).is_null());
        // a null/empty body is treated as an empty slice, not dereferenced
        assert!(oura_decode_event(0x42, std::ptr::null(), 0).is_null());
    }

    #[test]
    fn string_free_tolerates_null() {
        oura_string_free(std::ptr::null_mut());
    }
}
