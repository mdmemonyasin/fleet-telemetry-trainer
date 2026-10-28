"""M1 tests: validation + publish to Kafka (Kafka replaced by a fake)."""
import datetime as dt

import pytest


def test_valid_heartbeat_is_accepted_and_published(client, publisher, heartbeat):
    r = client.post("/v1/heartbeats", json=heartbeat)
    assert r.status_code == 202
    assert len(publisher.sent) == 1
    topic, key, value = publisher.sent[0]
    assert topic == "telemetry.raw"
    assert key == heartbeat["vin"]                      # keyed by VIN -> per-vehicle ordering
    assert value["schema"] == "v1"
    assert value["odometer_km"] == 1000.0


def test_vin_is_normalised_to_upper_case(client, publisher, heartbeat):
    heartbeat["vin"] = heartbeat["vin"].lower()
    assert client.post("/v1/heartbeats", json=heartbeat).status_code == 202
    assert publisher.sent[0][1] == heartbeat["vin"].upper()


@pytest.mark.parametrize("field,value", [
    ("vin", "TOO-SHORT"),
    ("vin", "1HGCM82633A0O4352"),          # contains O
    ("speed_kmph", -5),
    ("lat", 123.0),
    ("odometer_unit", "furlong"),
    ("ts", "27/10/2026 10:00"),
    ("ts", "2026-10-27T10:00:00"),         # no timezone
])
def test_invalid_field_returns_400_with_error_body(client, publisher, heartbeat, field, value):
    heartbeat[field] = value
    r = client.post("/v1/heartbeats", json=heartbeat)
    assert r.status_code == 400
    body = r.json()
    assert body["error"] == "validation_failed"
    assert any(field in d["field"] for d in body["details"])
    assert publisher.sent == []                         # nothing published for bad input


def test_future_timestamp_rejected(client, heartbeat):
    future = dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1)
    heartbeat["ts"] = future.strftime("%Y-%m-%dT%H:%M:%SZ")
    assert client.post("/v1/heartbeats", json=heartbeat).status_code == 400


def test_missing_vin_returns_400(client, heartbeat):
    heartbeat.pop("vin")
    assert client.post("/v1/heartbeats", json=heartbeat).status_code == 400
