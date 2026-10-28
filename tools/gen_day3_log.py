"""Generate the Day 3 data files deterministically (commit the output to both repos):

  data/day3/app.log                          ~2,000 log lines for script task 2 (fixed timestamps)
  data/day3/transfer_cityride_to_autofin.csv FIX-3: CityRide's 25 vehicles + 2 unknown VINs + 1 duplicate row

Usage: python3 tools/gen_day3_log.py <repo_root>
"""
import csv
import datetime as dt
import random
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
out = root / "data" / "day3"
out.mkdir(parents=True, exist_ok=True)
rnd = random.Random(3)

with open(root / "data" / "vehicles.csv") as f:
    vehicles = list(csv.DictReader(f))
vins = [v["vin"] for v in vehicles]

# --- app.log --------------------------------------------------------------------------------------
END = dt.datetime(2026, 10, 27, 10, 0, 0, tzinfo=dt.timezone.utc)   # last line; the window is END-2h .. END
START = END - dt.timedelta(hours=2)
N = 2000
SERVICES = ["ingest", "consumer", "api", "billing"]
MESSAGES = {
    "INFO": ["heartbeat accepted", "event written", "GET /v1/vehicles/latest 200", "invoice line created"],
    "WARN": ["slow query 1200ms", "retrying publish", "odometer jump > 500 km", "consumer lag 350"],
    "ERROR": ["timeout calling OEM API", "db connection refused", "failed to parse message", "duplicate key on insert"],
}
# Errors cluster in the last hour for billing and consumer, so the "last hour" answer differs from "all day".
seconds = sorted(rnd.randint(0, 2 * 3600 - 1) for _ in range(N - 1)) + [2 * 3600]
lines = []
for i, s in enumerate(seconds):
    ts = START + dt.timedelta(seconds=s)
    stamp = ts.strftime("%Y-%m-%dT%H:%M:%SZ")
    last_hour = s >= 3600
    svc = rnd.choices(SERVICES, weights=[3, 3, 3, 2])[0]
    err_w = (6 if svc in ("billing", "consumer") else 2) if last_hour else 2
    level = rnd.choices(["INFO", "WARN", "ERROR"], weights=[16, 3, err_w])[0]
    msg = rnd.choice(MESSAGES[level])
    if i == len(seconds) - 1:
        lines.append(f"{stamp} ERROR [billing] vin={vins[0]} timeout calling OEM API")
        continue
    r = rnd.random()
    if r < 0.006:                      # malformed lines
        lines.append(rnd.choice([
            f"{stamp} ERROR billing timeout calling OEM API",   # no [service]
            "Traceback (most recent call last):",
            f"{stamp[:10]} {level} [{svc}] truncated",
            "",
        ]))
    elif r < 0.15:                     # no vin
        lines.append(f"{stamp} {level} [{svc}] {msg}")
    else:
        lines.append(f"{stamp} {level} [{svc}] vin={rnd.choice(vins)} {msg}")
(out / "app.log").write_text("\n".join(lines) + "\n")

# --- FIX-3 CSV --------------------------------------------------------------------------------------
cityride = sorted(v["vin"] for v in vehicles if v["customer_id"] == "2")
assert len(cityride) == 25
VIN_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
fake = []
while len(fake) < 2:                    # valid-looking VINs that don't exist
    v = "2GB" + "".join(rnd.choice(VIN_CHARS) for _ in range(14))
    if v not in vins:
        fake.append(v)
rows = list(cityride)
rows.insert(8, fake[0])
rows.insert(19, fake[1])
rows.insert(23, cityride[4])            # duplicated row
assert len(rows) == 28
with open(out / "transfer_cityride_to_autofin.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["vin", "from_customer_id", "to_customer_id"])
    for v in rows:
        w.writerow([v, 2, 5])

print(f"app.log lines={len(lines)} first={START:%Y-%m-%dT%H:%M:%SZ} last={END:%Y-%m-%dT%H:%M:%SZ}")
print(f"csv rows={len(rows)} unknown={fake} duplicate={cityride[4]}")
