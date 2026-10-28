"""Request / event models. Validation rules live here so the API returns clear 400s."""
import datetime as dt
import re
from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator

VIN_RE = re.compile(r"^[A-HJ-NPR-Z0-9]{17}$")   # 17 chars, no I / O / Q
MAX_CLOCK_SKEW = dt.timedelta(minutes=5)


class HeartbeatRequest(BaseModel):
    """What a device (or OEM feed) sends to POST /v1/heartbeats."""
    vin: str
    ts: dt.datetime                                   # ISO-8601 with timezone, e.g. 2026-10-28T10:00:00Z
    speed_kmph: float = Field(ge=0, le=300)
    odometer: float = Field(ge=0)
    odometer_unit: Literal["km", "mi"] = "km"
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    heading: Optional[int] = Field(default=None, ge=0, le=359)
    soc_pct: Optional[float] = Field(default=None, ge=0, le=100)
    ignition: bool = True
    source_oem: str = Field(min_length=1, max_length=32)

    @field_validator("vin")
    @classmethod
    def vin_format(cls, v: str) -> str:
        v = v.strip().upper()
        if not VIN_RE.match(v):
            raise ValueError("vin must be 17 characters A-Z/0-9 excluding I, O, Q")
        return v

    @field_validator("ts")
    @classmethod
    def ts_has_tz_and_not_future(cls, v: dt.datetime) -> dt.datetime:
        if v.tzinfo is None:
            raise ValueError("ts must include a timezone (e.g. trailing Z)")
        v = v.astimezone(dt.timezone.utc)
        if v > dt.datetime.now(dt.timezone.utc) + MAX_CLOCK_SKEW:
            raise ValueError("ts is in the future")
        return v


class TelemetryEvent(BaseModel):
    """Normalised event written to Kafka topic telemetry.raw (schema v1). Odometer is always km."""
    schema_version: Literal["v1"] = Field(default="v1", alias="schema")
    vin: str
    ts_utc: dt.datetime
    speed_kmph: float
    odometer_km: float
    lat: float
    lon: float
    heading: Optional[int] = None
    soc_pct: Optional[float] = None
    ignition: bool = True
    source_oem: str

    model_config = {"populate_by_name": True}

    def to_message(self) -> dict:
        d = self.model_dump(mode="json", by_alias=True)
        return d
