"""Shared stdlib helpers for the tools/ runners (run_models, run_activity_model,
run_sleep_model, run_cva_model, run_spo2, inspect_models). Imported as a sibling
module — each runner's directory is on sys.path[0] when invoked as
`python tools/run_*.py`.

Torch-dependent helpers live in `_models.py`, so runners that need no model
(e.g. run_spo2) stay importable without torch installed.
"""
import datetime
import json
import sqlite3
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO / "notes" / "models"

BEDTIME_TAG = 0x76  # bedtime_period

# Plausible inter-beat interval (ms): outside this range a beat is not a beat.
IBI_MIN_MS = 300
IBI_MAX_MS = 2000


def resolve_db(arg, repo=REPO):
    """Resolve the SQLite events DB path.

    Explicit ``arg`` wins; otherwise pick the first existing default among
    ./oura.db, repo/oura.db, repo/captures/ring5.db, falling back to
    repo/oura.db. Exit with a clear error if the resolved DB is missing.
    """
    if arg:
        db = Path(arg)
    else:
        db = next(
            (c for c in (Path.cwd() / "oura.db", repo / "oura.db",
                         repo / "captures" / "ring5.db") if c.exists()),
            repo / "oura.db",
        )
    if not db.exists():
        sys.exit(f"error: database not found: {db} (run `oura sync` first)")
    return db


def connect(db):
    """Open the events DB (accepts a path or an already-resolved Path)."""
    return sqlite3.connect(str(db))


def decoded_events(con, key="name"):
    """All decoded events as ``(ring_timestamp, key_column, decoded_json,
    captured_unix)`` rows, oldest first. ``key`` selects the second column:
    the event ``name`` or its numeric ``tag``.
    """
    if key not in ("name", "tag"):
        raise ValueError(f"key must be 'name' or 'tag', got {key!r}")
    return con.execute(
        f"SELECT ring_timestamp, {key}, decoded_json, captured_unix FROM events "
        "WHERE decoded_json IS NOT NULL ORDER BY ring_timestamp"
    ).fetchall()


class DsClock:
    """Ring deciseconds -> absolute time.

    The ring counts deciseconds since its own boot, so the timeline is anchored
    on the newest event's capture time and every other event is placed relative
    to it. Built from `decoded_events` rows (`[0]` = ring ds, `[-1]` = capture
    unix seconds).
    """

    def __init__(self, rows):
        if not rows:
            sys.exit("error: no decoded events in DB (run `oura sync` first)")
        self.max_ds, self.anchor_unix = max(((r[0], r[-1]) for r in rows), key=lambda x: x[0])

    def unix(self, ds):
        """Unix seconds (float) for a ring decisecond stamp."""
        return self.anchor_unix - (self.max_ds - ds) / 10.0

    def ms(self, ds):
        """Unix milliseconds (int64) — the time axis every model input shares."""
        return int(self.anchor_unix * 1000 - (self.max_ds - ds) * 100)

    def minutes(self, ds):
        """Unix minutes (float), the unit the activity model works in."""
        return self.unix(ds) / 60.0


def latest_bedtime(con, hint="sync overnight data first"):
    """The most recent decoded `bedtime_period` (tag 0x76) in the DB, or exit."""
    row = con.execute(
        "SELECT decoded_json FROM events WHERE tag=? ORDER BY ring_timestamp DESC",
        (BEDTIME_TAG,),
    ).fetchone()
    if row is None:
        sys.exit(f"no bedtime_period (tag 0x{BEDTIME_TAG:02x}) in DB — {hint}")
    return json.loads(row[0])


def latest_bedtime_from_rows(rows, hint="sync overnight data first"):
    """Same as `latest_bedtime`, from already-fetched `decoded_events` rows."""
    beds = [json.loads(r[2]) for r in rows if r[1] in ("bedtime_period", BEDTIME_TAG)]
    if not beds:
        sys.exit(f"no bedtime_period (tag 0x{BEDTIME_TAG:02x}) in DB — {hint}")
    return beds[-1]


def ibi_valid(ibi_ms):
    """Whether an inter-beat interval is physiologically plausible (the quality
    flag the sleep models expect alongside each beat)."""
    return IBI_MIN_MS <= ibi_ms <= IBI_MAX_MS


def local_dt(unix_s, tz_hours=0):
    """Naive local datetime for `unix_s` shifted by a whole-hour UTC offset."""
    shifted = datetime.datetime.fromtimestamp(
        unix_s + tz_hours * 3600, tz=datetime.timezone.utc
    )
    return shifted.replace(tzinfo=None)
