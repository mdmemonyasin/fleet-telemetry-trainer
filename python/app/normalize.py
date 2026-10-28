"""Turn a device heartbeat into the normalised TelemetryEvent (all units metric, all times UTC)."""
from app.models import HeartbeatRequest, TelemetryEvent

KM_PER_MILE = 1.609344


def to_km(value: float, unit: str) -> float:
    if unit == "mi":
        return value * KM_PER_MILE
    if unit == "km":
        return value
    raise ValueError(f"unknown odometer unit: {unit}")


def normalize(hb: HeartbeatRequest) -> TelemetryEvent:
    return TelemetryEvent(
        vin=hb.vin,
        ts_utc=hb.ts,
        speed_kmph=round(hb.speed_kmph, 2),
        odometer_km=round(to_km(hb.odometer, hb.odometer_unit), 1),
        lat=hb.lat,
        lon=hb.lon,
        heading=hb.heading,
        soc_pct=hb.soc_pct,
        ignition=hb.ignition,
        source_oem=hb.source_oem,
    )
