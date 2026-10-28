"""M2/M3 tests against the real Postgres from docker compose. Skipped if the DB is not running."""
import datetime as dt
import json
import uuid

import pytest

from app.consumer import parse, process
from app.db import db_is_up, get_pool
from app.main import app  # noqa: F401  (ensures app imports cleanly)

pytestmark = pytest.mark.skipif(not db_is_up(), reason="Postgres not running (docker compose up -d)")


def unique_vin():
    return ("9ZZ" + uuid.uuid4().hex.upper().replace("O", "0").replace("I", "1"))[:17].replace("Q", "0")


def event(vin, seconds, speed=50.0):
    ts = dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=seconds)
    return parse(json.dumps({"schema": "v1", "vin": vin, "ts_utc": ts.isoformat(), "speed_kmph": speed,
                             "odometer_km": 100.0, "lat": 12.9, "lon": 77.5, "source_oem": "TEST"}).encode())


@pytest.fixture
def conn():
    with get_pool().connection() as c:
        yield c
        c.rollback()          # leave no test data behind


def test_insert_is_idempotent(conn, publisher):
    vin = unique_vin()
    assert process(conn, event(vin, 0), publisher) is True
    assert process(conn, event(vin, 0), publisher) is False      # same (vin, ts) -> ignored
    n = conn.execute("SELECT count(*) FROM telemetry WHERE vin = %s", (vin,)).fetchone()[0]
    assert n == 1


def test_overspeed_alert_created_once(conn, publisher):
    vin = unique_vin()
    for i, s in enumerate([60, 105, 110, 120, 125]):
        process(conn, event(vin, 5 * i, s), publisher)
    alerts = conn.execute("SELECT type, started_at FROM alerts WHERE vin = %s", (vin,)).fetchall()
    assert len(alerts) == 1
    assert alerts[0][0] == "OVERSPEED"
    assert [t for t, *_ in publisher.sent].count("alerts.out") == 1


def test_wrong_schema_fails_parse():
    with pytest.raises(ValueError):
        parse(b'{"schema": "v2", "vin": "X", "speed": {"value": 50}}')


def test_fleet_latest_endpoint_one_row_per_vehicle(client):
    r = client.get("/v1/fleets/1/latest")
    assert r.status_code == 200
    vins = [i["vin"] for i in r.json()["items"]]
    assert len(vins) == len(set(vins)) > 0


def test_latest_404_for_unknown_vin(client):
    assert client.get("/v1/vehicles/1FAXXXXXXXXXXXXXX/latest").status_code == 404


def test_telemetry_pagination(client):
    vin = client.get("/v1/fleets/1/latest").json()["items"][0]["vin"]
    params = {"from": "2000-01-01T00:00:00Z", "to": "2100-01-01T00:00:00Z", "limit": 100}
    p1 = client.get(f"/v1/vehicles/{vin}/telemetry", params=params).json()
    assert len(p1["items"]) == 100 and p1["next_cursor"]
    p2 = client.get(f"/v1/vehicles/{vin}/telemetry", params={**params, "cursor": p1["next_cursor"]}).json()
    assert p2["items"][0]["ts_utc"] > p1["items"][-1]["ts_utc"]
