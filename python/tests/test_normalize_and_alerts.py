import datetime as dt

import pytest

from app.alerts import overspeed_run_start
from app.models import HeartbeatRequest
from app.normalize import normalize, to_km

T0 = dt.datetime(2026, 1, 15, 10, 0, tzinfo=dt.timezone.utc)


def hb(**kw):
    base = dict(vin="2GBXXK7Y3R5A2B4C6", ts=T0, speed_kmph=50, odometer=100, odometer_unit="km",
                lat=12.9, lon=77.5, source_oem="OEM-B")
    base.update(kw)
    return HeartbeatRequest(**base)


def test_km_is_unchanged():
    assert normalize(hb(odometer=100, odometer_unit="km")).odometer_km == 100.0


def test_miles_converted_once():
    assert normalize(hb(odometer=100, odometer_unit="mi")).odometer_km == 160.9


def test_unknown_unit_raises():
    with pytest.raises(ValueError):
        to_km(1, "furlong")


def test_ist_timestamp_stored_as_utc():
    ist = dt.timezone(dt.timedelta(hours=5, minutes=30))
    e = normalize(hb(ts=dt.datetime(2026, 1, 15, 15, 30, tzinfo=ist)))
    assert e.ts_utc == T0


def rows(*speeds):
    """speeds given oldest -> newest; returns newest-first rows like the DB query."""
    r = [(T0 + dt.timedelta(seconds=5 * i), s) for i, s in enumerate(speeds)]
    return list(reversed(r))


def test_three_consecutive_over_limit_triggers():
    assert overspeed_run_start(rows(60, 105, 110, 120), 100, 3) == T0 + dt.timedelta(seconds=5)


def test_two_over_limit_does_not_trigger():
    assert overspeed_run_start(rows(60, 105, 110), 100, 3) is None


def test_exactly_limit_is_not_overspeed():
    assert overspeed_run_start(rows(100, 100, 100), 100, 3) is None


def test_run_broken_by_lower_speed_resets():
    assert overspeed_run_start(rows(105, 110, 60, 120, 130), 100, 3) is None


def test_start_is_stable_as_run_grows():
    assert overspeed_run_start(rows(60, 105, 110, 120), 100, 3) == overspeed_run_start(rows(60, 105, 110, 120, 125), 100, 3)
