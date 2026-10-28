"""All SQL lives here. Parameterised queries only (never f-strings with user input)."""
import datetime as dt
import json
from typing import Optional

from psycopg.rows import dict_row

from app.db import get_pool
from app.models import TelemetryEvent

INSERT_TELEMETRY = """
INSERT INTO telemetry (vin, ts_utc, speed_kmph, odometer_km, lat, lon, heading, soc_pct, ignition, source_oem)
VALUES (%(vin)s, %(ts_utc)s, %(speed_kmph)s, %(odometer_km)s, %(lat)s, %(lon)s, %(heading)s, %(soc_pct)s, %(ignition)s, %(source_oem)s)
ON CONFLICT (vin, ts_utc) DO NOTHING
"""


def insert_telemetry(conn, e: TelemetryEvent) -> bool:
    """Idempotent insert. Returns True if a new row was written, False if it was a duplicate."""
    cur = conn.execute(INSERT_TELEMETRY, e.model_dump())
    return cur.rowcount == 1


def recent_speeds(conn, vin: str, limit: int = 50):
    rows = conn.execute(
        "SELECT ts_utc, speed_kmph FROM telemetry WHERE vin = %s ORDER BY ts_utc DESC LIMIT %s", (vin, limit)
    ).fetchall()
    return [(r[0], float(r[1])) for r in rows]


def insert_alert(conn, vin: str, alert_type: str, started_at: dt.datetime, details: dict) -> bool:
    cur = conn.execute(
        "INSERT INTO alerts (vin, type, started_at, details) VALUES (%s, %s, %s, %s::jsonb) "
        "ON CONFLICT (vin, type, started_at) DO NOTHING",
        (vin, alert_type, started_at, json.dumps(details)),
    )
    return cur.rowcount == 1


def latest_for_vin(vin: str) -> Optional[dict]:
    with get_pool().connection() as conn:
        conn.row_factory = dict_row
        return conn.execute(
            "SELECT vin, ts_utc, speed_kmph, odometer_km, lat, lon, heading, soc_pct, ignition, source_oem "
            "FROM telemetry WHERE vin = %s ORDER BY ts_utc DESC LIMIT 1", (vin,)
        ).fetchone()


def telemetry_range(vin: str, start: dt.datetime, end: dt.datetime, limit: int, after: Optional[dt.datetime]) -> list:
    """Keyset pagination on ts_utc: stable and fast with the (vin, ts_utc) index (no OFFSET)."""
    with get_pool().connection() as conn:
        conn.row_factory = dict_row
        return conn.execute(
            "SELECT vin, ts_utc, speed_kmph, odometer_km, lat, lon, heading, soc_pct, ignition, source_oem "
            "FROM telemetry WHERE vin = %s AND ts_utc >= %s AND ts_utc < %s AND ts_utc > %s "
            "ORDER BY ts_utc ASC LIMIT %s",
            (vin, start, end, after or dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc), limit),
        ).fetchall()


def alerts_for_vin(vin: str, limit: int = 50) -> list:
    with get_pool().connection() as conn:
        conn.row_factory = dict_row
        return conn.execute(
            "SELECT id, vin, type, started_at, details, created_at FROM alerts WHERE vin = %s "
            "ORDER BY started_at DESC LIMIT %s", (vin, limit)
        ).fetchall()


def fleet_latest(customer_id: int) -> list:
    """Latest position of every active vehicle of a customer. VIN match is case-insensitive (FLT-482)."""
    with get_pool().connection() as conn:
        conn.row_factory = dict_row
        vins = [r["vin"] for r in conn.execute(
            "SELECT vin FROM vehicles WHERE customer_id = %s AND status = 'active'", (customer_id,)).fetchall()]
        items = []
        for vin in vins:
            row = conn.execute(
                "SELECT vin, ts_utc, speed_kmph, lat, lon FROM telemetry "
                "WHERE upper(vin) = upper(%s) ORDER BY ts_utc DESC LIMIT 1", (vin,)).fetchone()
            if row:
                items.append(row)
        return items
