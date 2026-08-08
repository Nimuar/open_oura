#!/usr/bin/env python3
"""Run Oura's decrypted SleepNet (moonstone) model on our stored ring data to
extract a per-30s hypnogram (DEEP/LIGHT/REM/WAKE).

Inputs from the SQLite event log: IBI (0x60), motion_seconds (0x47), temp (0x46),
bedtime (0x76). SpO2 passed empty (we only have R-ratio, not %). Time axis is the
device-relative deciseconds anchored to the latest event's captured_unix.

Usage: python tools/run_sleep_model.py START_DS END_DS [DB] [TZ=1]
       (no args → uses the bedtime_period in the DB)
"""
import json
import sys

import torch

from _common import DsClock, connect, decoded_events, ibi_valid, latest_bedtime, local_dt, resolve_db
from _models import f32, i64, load_model

TZ = 1
STAGE = {1: "DEEP", 2: "LIGHT", 3: "REM", 4: "WAKE"}

args = [a for a in sys.argv[1:]]
start_ds = end_ds = None
if len(args) >= 2 and args[0].isdigit():
    start_ds, end_ds = int(args[0]), int(args[1])
    rest = args[2:]
else:
    rest = args
db_arg = rest[0] if rest else None
if len(rest) > 1:
    TZ = int(rest[1])
DB = resolve_db(db_arg)

con = connect(DB)
rows = decoded_events(con, key="tag")
clock = DsClock(rows)
ms = clock.ms  # device deciseconds -> absolute epoch ms (int64), shared by every signal

if start_ds is None:  # default: most recent bedtime_period in the DB
    v = latest_bedtime(con, hint="pass start/end deciseconds or sync overnight data first")
    start_ds, end_ds = v["bedtime_start_ds"], v["bedtime_end_ds"]

lo, hi = start_ds - 6000, end_ds + 6000  # ±10 min margin
beats, acm, temp = [], [], []
for ds, tag, js, _ in rows:
    if not (lo <= ds <= hi):
        continue
    v = json.loads(js)
    if tag in (0x60, 0x80) and v.get("ibi_ms"):  # ibi_and_amplitude + green_ibi_quality
        ibi = v["ibi_ms"]; amp = v.get("amplitude", [0] * len(ibi))
        t = ms(ds); acc = 0
        for i, x in enumerate(ibi):
            if x <= 0:  # zero/negative IBI can't advance the beat clock — skip (matches run_bdi)
                continue
            acc += x
            valid = 1 if ibi_valid(x) else 0
            beats.append((t + acc, float(x), float(amp[i] if i < len(amp) else 0), valid))
    elif tag == 0x47 and v.get("motion_seconds") is not None:
        acm.append((ms(ds), float(v["motion_seconds"])))
    elif tag == 0x46 and v.get("temps_c"):
        temp.append((ms(ds), float(v["temps_c"][0])))

beats.sort(); acm.sort(); temp.sort()
print(f"window ds [{start_ds}..{end_ds}] ({(end_ds-start_ds)/10/3600:.1f}h)  "
      f"beats={len(beats)} acm={len(acm)} temp={len(temp)}")
if not beats or not any(b[3] == 1 for b in beats):
    sys.exit("not enough valid IBI in this window")

def col(seq, i):
    return [r[i] for r in seq]
ibi_ts = i64(col(beats, 0))
ibi_val = f32([[b[1], b[2], b[3]] for b in beats], cols=3)
acm_ts = i64(col(acm, 0))
acm_val = f32([[a[1]] for a in acm], cols=1)
temp_ts = i64(col(temp, 0))
temp_val = f32([[t[1]] for t in temp], cols=1)
bedtime = i64([ms(start_ds), ms(end_ds)])
spo2_val = torch.empty(0, 1, dtype=torch.float32)
spo2_ts = torch.empty(0, dtype=torch.int64)
scalars = f32([35, 25, 0, 0, 0])
tst = f32([300.0])

m = load_model("sleepnet_moonstone_1_2_0")
with torch.no_grad():
    ts, staging, apnea, spo2_out, metrics, debug = m(
        bedtime, ibi_val, ibi_ts, acm_val, acm_ts, temp_val, temp_ts,
        spo2_val, spo2_ts, scalars, tst)

stages = [int(s) for s in staging[:, 0].tolist()]
n = len(stages)
if n == 0:
    sys.exit("SleepNet-moonstone returned zero epochs for this window")
mins = {k: stages.count(c) * 0.5 for c, k in STAGE.items()}
asleep = n * 0.5 - mins["WAKE"]
print(f"\nHypnogram: {n} epochs = {n*0.5:.0f} min in bed")
for k in ["DEEP", "LIGHT", "REM", "WAKE"]:
    pct = 100 * mins[k] / (n * 0.5) if n else 0
    print(f"  {k:<6} {mins[k]:>6.0f} min  ({pct:4.0f}%)")
print(f"  asleep {asleep:.0f} min,  sleep efficiency {100*asleep/(n*0.5):.0f}%")

# compact timeline: one glyph per ~10 min (20 epochs), majority stage
g = {1: "D", 2: "L", 3: "R", 4: "W"}
def hm(ms_):
    return local_dt(ms_ / 1000, TZ).strftime("%H:%M")
print(f"\n  {hm(int(ts[0]))} ", end="")
for i in range(0, n, 20):
    blk = stages[i:i+20]
    maj = max(set(blk), key=blk.count)
    print(g.get(maj, "?"), end="")
print(f" {hm(int(ts[-1]))}   (D=deep L=light R=rem W=wake, ~10min/char)")
