"""FIX-3 answer (TRAINER ONLY): move vehicles between customers from a CSV, safely.

  python trainer/day3_fix3_transfer.py data/day3/transfer_cityride_to_autofin.csv --dry-run
  python trainer/day3_fix3_transfer.py data/day3/transfer_cityride_to_autofin.csv

Run from the repo root with the Python track's venv (it has psycopg): python/.venv/bin/python ...
DB settings come from the same env vars as the app (DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD).

What it does:
  1. Reads every row and checks it: VIN format, numbers, duplicate rows, the vehicle exists, the vehicle
     really belongs to from_customer_id, and to_customer_id exists. Bad rows are reported and skipped.
  2. --dry-run prints what would change and stops (no writes).
  3. Otherwise backs up the rows, applies every change in ONE transaction, checks each UPDATE changed
     exactly 1 row (else rolls everything back), writes one audit_log line, and commits.
Running it again is safe: moved vehicles no longer belong to from_customer_id, so they're skipped.
"""
import argparse
import csv
import os
import re
import sys

import psycopg

VIN_RE = re.compile(r"^[A-HJ-NPR-Z0-9]{17}$")


def dsn() -> str:
    return (f"host={os.getenv('DB_HOST', 'localhost')} port={os.getenv('DB_PORT', '5432')} "
            f"dbname={os.getenv('DB_NAME', 'fleet')} user={os.getenv('DB_USER', 'fleet')} "
            f"password={os.getenv('DB_PASSWORD', 'fleet')}")


def read_rows(path):
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        expected = {"vin", "from_customer_id", "to_customer_id"}
        if set(reader.fieldnames or []) != expected:
            sys.exit(f"bad header {reader.fieldnames}, expected {sorted(expected)}")
        return [(n, row) for n, row in enumerate(reader, start=2)]   # line 1 is the header


def main() -> int:
    ap = argparse.ArgumentParser(description="Move vehicles between customers from a CSV.")
    ap.add_argument("csv")
    ap.add_argument("--dry-run", action="store_true", help="only print what would change")
    ap.add_argument("--ticket", default="FIX-3")
    a = ap.parse_args()

    good, problems, seen = [], [], set()
    with psycopg.connect(dsn()) as conn:
        customers = {r[0] for r in conn.execute("SELECT id FROM customers")}
        owner = dict(conn.execute("SELECT vin, customer_id FROM vehicles").fetchall())

        for line, row in read_rows(a.csv):
            vin = (row["vin"] or "").strip().upper()
            try:
                src, dst = int(row["from_customer_id"]), int(row["to_customer_id"])
            except (TypeError, ValueError):
                problems.append((line, vin, "customer ids must be numbers")); continue
            if not VIN_RE.match(vin):
                problems.append((line, vin, "not a valid VIN")); continue
            if vin in seen:
                problems.append((line, vin, "duplicate row (already listed above), skipped")); continue
            seen.add(vin)
            if vin not in owner:
                problems.append((line, vin, "vehicle does not exist")); continue
            if dst not in customers:
                problems.append((line, vin, f"to_customer_id {dst} does not exist")); continue
            if owner[vin] != src:
                problems.append((line, vin, f"belongs to customer {owner[vin]}, not {src} (already moved?)")); continue
            good.append((vin, src, dst))

        for line, vin, why in problems:
            print(f"SKIP line {line}: {vin or '(empty)'}: {why}")
        print(f"{len(good)} vehicle(s) to move, {len(problems)} row(s) skipped")

        if a.dry_run:
            for vin, src, dst in good:
                print(f"  would move {vin}: customer {src} -> {dst}")
            print("dry run: nothing written")
            return 0
        if not good:
            print("nothing to do")
            return 0

        with conn.transaction():
            conn.execute("CREATE TABLE IF NOT EXISTS bkp_fix_3_script AS SELECT * FROM vehicles WITH NO DATA")
            conn.execute("INSERT INTO bkp_fix_3_script SELECT * FROM vehicles WHERE vin = ANY(%s)",
                         ([v for v, _, _ in good],))
            for vin, src, dst in good:
                cur = conn.execute("UPDATE vehicles SET customer_id = %s WHERE vin = %s AND customer_id = %s",
                                   (dst, vin, src))
                if cur.rowcount != 1:
                    raise RuntimeError(f"{vin}: expected 1 row changed, got {cur.rowcount}; rolled back")
            conn.execute("INSERT INTO audit_log (table_name, change, ticket) VALUES ('vehicles', %s, %s)",
                         (f"{len(good)} vehicles moved via CSV {os.path.basename(a.csv)}; {len(problems)} rows skipped",
                          a.ticket))
        print(f"done: {len(good)} vehicle(s) moved in one transaction")
    return 0


if __name__ == "__main__":
    sys.exit(main())
