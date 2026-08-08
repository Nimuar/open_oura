//! Optional SQLite persistence (feature `storage`).
//!
//! Events are stored with their raw body retained, so unknown event types are
//! never lost and can be decoded later. A per-device sync cursor enables
//! incremental syncs. Re-syncing is idempotent: identical events are de-duped.

use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{params, Connection, OptionalExtension};

use oura_protocol::device::{Battery, DeviceInfo};
use crate::error::Result;
use oura_protocol::events::RingEvent;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS device (
    serial        TEXT PRIMARY KEY,
    hardware_id   TEXT,
    firmware      TEXT,
    api_version   TEXT,
    mac           TEXT,
    updated_unix  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_state (
    serial        TEXT PRIMARY KEY,
    next_cursor   INTEGER NOT NULL,
    last_sync_unix INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    serial         TEXT NOT NULL,
    tag            INTEGER NOT NULL,
    name           TEXT NOT NULL,
    ring_timestamp INTEGER NOT NULL,
    body           BLOB NOT NULL,
    decoded_json   TEXT,
    captured_unix  INTEGER NOT NULL,
    UNIQUE(serial, tag, ring_timestamp, body)
);
CREATE INDEX IF NOT EXISTS idx_events_serial_tag ON events(serial, tag);

CREATE TABLE IF NOT EXISTS readings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    serial        TEXT NOT NULL,
    kind          TEXT NOT NULL,
    value         REAL NOT NULL,
    unit          TEXT,
    captured_unix INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_readings_serial_kind ON readings(serial, kind);
"#;

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// A SQLite-backed store for ring data.
pub struct Store {
    conn: Connection,
}

impl Store {
    /// Open (creating if needed) a database at `path` and ensure the schema.
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(SCHEMA)?;
        Ok(Self { conn })
    }

    /// Open an in-memory database (useful for tests).
    pub fn open_in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(SCHEMA)?;
        Ok(Self { conn })
    }

    /// Record/refresh device metadata.
    pub fn upsert_device(
        &self,
        serial: &str,
        hardware_id: Option<&str>,
        info: Option<&DeviceInfo>,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO device (serial, hardware_id, firmware, api_version, mac, updated_unix)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(serial) DO UPDATE SET
               hardware_id=COALESCE(excluded.hardware_id, device.hardware_id),
               firmware=COALESCE(excluded.firmware, device.firmware),
               api_version=COALESCE(excluded.api_version, device.api_version),
               mac=COALESCE(excluded.mac, device.mac),
               updated_unix=excluded.updated_unix",
            params![
                serial,
                hardware_id,
                info.map(|i| i.firmware_version.clone()),
                info.map(|i| i.api_version.clone()),
                info.map(|i| i.mac.clone()),
                now_unix(),
            ],
        )?;
        Ok(())
    }

    /// The persisted incremental-sync cursor (deciseconds), or 0 if none.
    pub fn cursor(&self, serial: &str) -> Result<u32> {
        let v: Option<i64> = self
            .conn
            .query_row(
                "SELECT next_cursor FROM sync_state WHERE serial = ?1",
                params![serial],
                |r| r.get(0),
            )
            .optional()?;
        Ok(v.unwrap_or(0) as u32)
    }

    /// Persist the next sync cursor.
    pub fn set_cursor(&self, serial: &str, cursor: u32) -> Result<()> {
        self.conn.execute(
            "INSERT INTO sync_state (serial, next_cursor, last_sync_unix)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(serial) DO UPDATE SET
               next_cursor=excluded.next_cursor,
               last_sync_unix=excluded.last_sync_unix",
            params![serial, cursor as i64, now_unix()],
        )?;
        Ok(())
    }

    /// Insert an event, ignoring exact duplicates. Returns true if a row was added.
    pub fn insert_event(&self, serial: &str, ev: &RingEvent) -> Result<bool> {
        let decoded = ev
            .decoded
            .as_ref()
            .map(|v| serde_json::to_string(v).unwrap_or_default());
        let changed = self.conn.execute(
            "INSERT OR IGNORE INTO events
               (serial, tag, name, ring_timestamp, body, decoded_json, captured_unix)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                serial,
                ev.tag as i64,
                ev.name,
                ev.timestamp as i64,
                ev.body,
                decoded,
                now_unix(),
            ],
        )?;
        Ok(changed > 0)
    }

    /// Record a scalar reading (e.g. live HR bpm, SpO2 %, battery %).
    pub fn insert_reading(&self, serial: &str, kind: &str, value: f64, unit: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO readings (serial, kind, value, unit, captured_unix)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![serial, kind, value, unit, now_unix()],
        )?;
        Ok(())
    }

    /// Convenience: store a battery reading.
    pub fn insert_battery(&self, serial: &str, battery: &Battery) -> Result<()> {
        self.insert_reading(serial, "battery_percent", battery.percent as f64, "%")
    }

    /// Re-decode every stored event body with the current decoders, updating
    /// `decoded_json`. Returns `(rows_with_decode, total_rows)`. Lets new decoders
    /// be applied to events captured before they existed — no re-sync needed.
    pub fn redecode(&self) -> Result<(usize, usize)> {
        let rows: Vec<(i64, i64, Vec<u8>)> = {
            let mut stmt = self.conn.prepare("SELECT id, tag, body FROM events")?;
            let collected = stmt
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            collected
        };
        let total = rows.len();
        let mut decoded_count = 0;
        for (id, tag, body) in rows {
            let decoded = oura_protocol::events::decode_event_body(tag as u8, &body)
                .map(|v| serde_json::to_string(&v).unwrap_or_default());
            if decoded.is_some() {
                decoded_count += 1;
            }
            let name = oura_protocol::events::event_name(tag as u8);
            self.conn.execute(
                "UPDATE events SET decoded_json = ?1, name = ?2 WHERE id = ?3",
                params![decoded, name, id],
            )?;
        }
        Ok((decoded_count, total))
    }

    /// All decoded events as `(ring_timestamp_deciseconds, tag, decoded_json,
    /// captured_unix)`, ordered by ring time. For analysis/reporting commands that
    /// reconstruct time series from stored events.
    pub fn decoded_events(&self) -> Result<Vec<(i64, u8, String, i64)>> {
        let mut stmt = self.conn.prepare(
            "SELECT ring_timestamp, tag, decoded_json, captured_unix FROM events \
             WHERE decoded_json IS NOT NULL ORDER BY ring_timestamp",
        )?;
        let rows = stmt
            .query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)? as u8,
                    r.get::<_, String>(2)?,
                    r.get::<_, i64>(3)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Distinct device serials that have stored events.
    pub fn device_serials(&self) -> Result<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare("SELECT DISTINCT serial FROM events ORDER BY serial")?;
        let rows = stmt
            .query_map([], |r| r.get(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Count stored events grouped by event name (descending).
    pub fn event_counts(&self, serial: &str) -> Result<Vec<(String, i64)>> {
        let mut stmt = self.conn.prepare(
            "SELECT name, COUNT(*) FROM events WHERE serial = ?1 GROUP BY name ORDER BY 2 DESC",
        )?;
        let rows = stmt
            .query_map(params![serial], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn events_dedup_and_cursor_roundtrip() {
        let store = Store::open_in_memory().unwrap();
        let ev = RingEvent {
            tag: 0x43,
            name: "debug_event",
            timestamp: 42,
            body: vec![1, 2, 3],
            decoded: None,
        };
        assert!(store.insert_event("S1", &ev).unwrap());
        assert!(!store.insert_event("S1", &ev).unwrap()); // duplicate ignored

        store.set_cursor("S1", 1234).unwrap();
        assert_eq!(store.cursor("S1").unwrap(), 1234);

        let counts = store.event_counts("S1").unwrap();
        assert_eq!(counts, vec![("debug_event".to_string(), 1)]);
    }

    fn event(tag: u8, timestamp: u32, body: Vec<u8>) -> RingEvent {
        RingEvent {
            tag,
            name: oura_protocol::events::event_name(tag),
            timestamp,
            body,
            decoded: None,
        }
    }

    #[test]
    fn cursor_defaults_to_zero_and_overwrites() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(store.cursor("unknown").unwrap(), 0);
        store.set_cursor("S1", 10).unwrap();
        store.set_cursor("S1", 20).unwrap();
        assert_eq!(store.cursor("S1").unwrap(), 20);
        assert_eq!(store.cursor("S2").unwrap(), 0);
    }

    #[test]
    fn upsert_device_keeps_known_fields_on_partial_update() {
        let store = Store::open_in_memory().unwrap();
        let info = DeviceInfo {
            api_version: "2.0.0".into(),
            firmware_version: "3.4.3".into(),
            bootloader_version: "1.0.1".into(),
            bt_stack_version: "5.0.0".into(),
            mac: "aa:bb:cc:dd:ee:ff".into(),
        };
        store.upsert_device("S1", Some("BLB_03"), Some(&info)).unwrap();
        // A later info-less upsert must not wipe the firmware/mac already stored.
        store.upsert_device("S1", None, None).unwrap();

        let (hardware_id, firmware, mac): (Option<String>, Option<String>, Option<String>) = store
            .conn
            .query_row(
                "SELECT hardware_id, firmware, mac FROM device WHERE serial = ?1",
                params!["S1"],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert_eq!(hardware_id.as_deref(), Some("BLB_03"));
        assert_eq!(firmware.as_deref(), Some("3.4.3"));
        assert_eq!(mac.as_deref(), Some("aa:bb:cc:dd:ee:ff"));
    }

    #[test]
    fn events_differing_in_body_are_both_stored() {
        let store = Store::open_in_memory().unwrap();
        assert!(store.insert_event("S1", &event(0x43, 1, vec![1])).unwrap());
        assert!(store.insert_event("S1", &event(0x43, 1, vec![2])).unwrap());
        assert_eq!(store.event_counts("S1").unwrap(), vec![("debug_event".to_string(), 2)]);
    }

    #[test]
    fn insert_event_persists_decoded_json() {
        let store = Store::open_in_memory().unwrap();
        let mut ev = event(0x42, 5, vec![0x01, 0x02, 0x03, 0x04]);
        ev.decoded = Some(serde_json::json!({ "unix_time": 67_305_985u32 }));
        assert!(store.insert_event("S1", &ev).unwrap());

        let decoded = store.decoded_events().unwrap();
        assert_eq!(decoded.len(), 1);
        let (ts, tag, json, _captured) = &decoded[0];
        assert_eq!((*ts, *tag), (5, 0x42));
        assert_eq!(json, "{\"unix_time\":67305985}");
    }

    #[test]
    fn decoded_events_skips_undecoded_and_orders_by_ring_time() {
        let store = Store::open_in_memory().unwrap();
        let mut later = event(0x42, 20, vec![0x02, 0, 0, 0]);
        later.decoded = Some(serde_json::json!({ "unix_time": 2 }));
        let mut earlier = event(0x42, 10, vec![0x01, 0, 0, 0]);
        earlier.decoded = Some(serde_json::json!({ "unix_time": 1 }));
        store.insert_event("S1", &later).unwrap();
        store.insert_event("S1", &earlier).unwrap();
        // No decoder for this tag, so it must not appear in decoded_events().
        store.insert_event("S1", &event(0x44, 15, vec![9])).unwrap();

        let timestamps: Vec<i64> = store.decoded_events().unwrap().iter().map(|r| r.0).collect();
        assert_eq!(timestamps, vec![10, 20]);
    }

    #[test]
    fn redecode_applies_current_decoders_and_fixes_names() {
        let store = Store::open_in_memory().unwrap();
        // Stored without a decode, and with a stale name, as an older client would.
        let mut stale = event(0x42, 7, vec![0x4f, 0xd2, 0x37, 0x6a]);
        stale.name = "unknown";
        store.insert_event("S1", &stale).unwrap();
        // A tag with no decoder: counted in the total but not as decoded.
        store.insert_event("S1", &event(0x44, 8, vec![1, 2])).unwrap();

        assert_eq!(store.redecode().unwrap(), (1, 2));
        let decoded = store.decoded_events().unwrap();
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].2, "{\"unix_time\":1782043215}");
        let mut names: Vec<String> = store.event_counts("S1").unwrap().into_iter().map(|c| c.0).collect();
        names.sort();
        assert_eq!(names, vec!["ibi_event", "time_sync"]);
    }

    #[test]
    fn device_serials_are_distinct_and_sorted() {
        let store = Store::open_in_memory().unwrap();
        store.insert_event("S2", &event(0x43, 1, vec![1])).unwrap();
        store.insert_event("S1", &event(0x43, 1, vec![1])).unwrap();
        store.insert_event("S2", &event(0x43, 2, vec![1])).unwrap();
        assert_eq!(store.device_serials().unwrap(), vec!["S1", "S2"]);
    }

    #[test]
    fn readings_and_battery_are_appended() {
        let store = Store::open_in_memory().unwrap();
        store.insert_reading("S1", "hr_bpm", 61.0, "bpm").unwrap();
        store.insert_reading("S1", "hr_bpm", 62.0, "bpm").unwrap();
        store
            .insert_battery(
                "S1",
                &Battery {
                    percent: 89,
                    charging_progress: 0,
                    charging_recommended: 0,
                },
            )
            .unwrap();

        let rows: Vec<(String, f64, String)> = {
            let mut stmt = store
                .conn
                .prepare("SELECT kind, value, unit FROM readings ORDER BY id")
                .unwrap();
            stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
                .unwrap()
                .collect::<std::result::Result<Vec<_>, _>>()
                .unwrap()
        };
        assert_eq!(
            rows,
            vec![
                ("hr_bpm".to_string(), 61.0, "bpm".to_string()),
                ("hr_bpm".to_string(), 62.0, "bpm".to_string()),
                ("battery_percent".to_string(), 89.0, "%".to_string()),
            ]
        );
    }

    #[test]
    fn open_persists_across_reopen() {
        let path = std::env::temp_dir().join(format!(
            "oura-store-test-{}-{:?}.sqlite",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_file(&path);

        {
            let store = Store::open(&path).unwrap();
            store.insert_event("S1", &event(0x43, 1, vec![1])).unwrap();
            store.set_cursor("S1", 99).unwrap();
        }
        {
            // Re-opening must find the existing schema and rows intact.
            let store = Store::open(&path).unwrap();
            assert_eq!(store.cursor("S1").unwrap(), 99);
            assert_eq!(store.device_serials().unwrap(), vec!["S1"]);
        }
        std::fs::remove_file(&path).unwrap();
    }

    #[test]
    fn open_reports_storage_error_for_unusable_path() {
        let err = match Store::open("/nonexistent-directory/oura.sqlite") {
            Err(e) => e,
            Ok(_) => panic!("expected open to fail"),
        };
        assert!(
            matches!(&err, crate::error::Error::Storage(msg) if msg.contains("unable to open")),
            "unexpected error: {err}"
        );
        assert!(err.to_string().starts_with("storage error: "));
    }
}
