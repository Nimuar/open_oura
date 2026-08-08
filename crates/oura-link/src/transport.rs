//! Transport abstraction over the ring's BLE link.
//!
//! The protocol is request/response with asynchronous notifications. [`Transport`]
//! captures just what the client needs — write a request, and subscribe to the
//! stream of inbound frames — so the higher layers can be exercised with a mock
//! in tests while [`crate::ble`] provides the real `btleplug` implementation.

use std::time::Duration;

use async_trait::async_trait;
use tokio::sync::broadcast;

use crate::error::Result;

/// A bidirectional link to a ring.
#[async_trait]
pub trait Transport: Send + Sync {
    /// Write a raw request frame to the ring's write characteristic.
    async fn write(&self, data: &[u8]) -> Result<()>;

    /// Subscribe to inbound notification frames (raw bytes, one per notification).
    fn subscribe(&self) -> broadcast::Receiver<Vec<u8>>;
}

/// Subscribe to inbound frames, dropping any backlog so only frames arriving
/// after this call are observed.
pub fn subscribe_fresh<T>(transport: &T) -> broadcast::Receiver<Vec<u8>>
where
    T: Transport + ?Sized,
{
    let mut rx = transport.subscribe();
    while rx.try_recv().is_ok() {}
    rx
}

/// Feed every inbound frame to `on_frame` for up to `duration`. A lagged
/// receiver skips the frames it missed rather than ending the stream; a closed
/// channel ends it early.
pub async fn stream_until<F>(
    rx: &mut broadcast::Receiver<Vec<u8>>,
    duration: Duration,
    mut on_frame: F,
) where
    F: FnMut(&[u8]),
{
    let deadline = tokio::time::Instant::now() + duration;
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        match tokio::time::timeout(remaining, rx.recv()).await {
            Ok(Ok(frame)) => on_frame(&frame),
            Ok(Err(broadcast::error::RecvError::Lagged(_))) => continue,
            // Channel closed or the window elapsed.
            _ => break,
        }
    }
}

/// Write `request` and collect notification frames until the link is quiet for
/// `quiet` (i.e. no new frame arrives within that window). This matches the
/// ring's behaviour of emitting one or more notifications per request with no
/// explicit terminator on most commands.
pub async fn transact<T>(transport: &T, request: &[u8], quiet: Duration) -> Result<Vec<Vec<u8>>>
where
    T: Transport + ?Sized,
{
    // Only observe responses to *this* request.
    let mut rx = subscribe_fresh(transport);

    transport.write(request).await?;

    let mut frames = Vec::new();
    loop {
        match tokio::time::timeout(quiet, rx.recv()).await {
            Ok(Ok(frame)) => frames.push(frame),
            Ok(Err(broadcast::error::RecvError::Lagged(_))) => continue,
            // Channel closed or quiet window elapsed: we're done collecting.
            _ => break,
        }
    }
    Ok(frames)
}

#[cfg(test)]
pub(crate) mod mock {
    //! A scripted transport for unit tests: maps request hex prefixes to canned
    //! response frames.
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    pub struct MockTransport {
        tx: broadcast::Sender<Vec<u8>>,
        responses: Mutex<HashMap<String, Vec<Vec<u8>>>>,
    }

    impl MockTransport {
        pub fn new() -> Self {
            let (tx, _) = broadcast::channel(64);
            Self {
                tx,
                responses: Mutex::new(HashMap::new()),
            }
        }

        /// Register canned responses keyed by the request's full hex.
        pub fn on(&self, request_hex: &str, responses: &[&str]) {
            self.responses.lock().unwrap().insert(
                request_hex.to_string(),
                responses.iter().map(|h| hex::decode(h).unwrap()).collect(),
            );
        }
    }

    #[async_trait]
    impl Transport for MockTransport {
        async fn write(&self, data: &[u8]) -> Result<()> {
            let key = hex::encode(data);
            if let Some(frames) = self.responses.lock().unwrap().get(&key) {
                for f in frames {
                    let _ = self.tx.send(f.clone());
                }
            }
            Ok(())
        }

        fn subscribe(&self) -> broadcast::Receiver<Vec<u8>> {
            self.tx.subscribe()
        }
    }
}
