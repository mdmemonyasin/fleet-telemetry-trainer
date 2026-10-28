"""FastAPI app. Run:  uvicorn app.main:app --reload --port 8080   (from the python/ folder)"""
import datetime as dt
import logging
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import config, repository
from app.db import db_is_up
from app.kafka_client import EventPublisher, get_publisher
from app.models import HeartbeatRequest
from app.normalize import normalize

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("api")

app = FastAPI(title="Fleet Telemetry API", version="1.0")


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError):
    details = [{"field": ".".join(str(p) for p in e["loc"][1:]) or "body", "message": e["msg"]} for e in exc.errors()]
    return JSONResponse(status_code=400, content={"error": "validation_failed", "details": details})


@app.get("/health")
def health():
    db = db_is_up()
    return JSONResponse(status_code=200 if db else 503,
                        content={"status": "UP" if db else "DEGRADED", "db": "up" if db else "down",
                                 "kafka_bootstrap": config.KAFKA_BOOTSTRAP})


# ---------------- M1: ingest ----------------
@app.post("/v1/heartbeats", status_code=202)
def ingest_heartbeat(hb: HeartbeatRequest, publisher: EventPublisher = Depends(get_publisher)):
    event = normalize(hb)
    publisher.publish(config.TOPIC_RAW, key=event.vin, value=event.to_message())
    return {"status": "accepted", "vin": event.vin, "ts_utc": event.ts_utc.isoformat()}


# ---------------- M3: query APIs ----------------
@app.get("/v1/vehicles/{vin}/latest")
def latest(vin: str):
    row = repository.latest_for_vin(vin.upper())
    if row is None:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": f"no telemetry for {vin}"})
    return row


@app.get("/v1/vehicles/{vin}/telemetry")
def telemetry(vin: str,
              start: dt.datetime = Query(alias="from"),
              end: dt.datetime = Query(alias="to"),
              limit: int = Query(default=100, ge=1, le=1000),
              cursor: Optional[dt.datetime] = Query(default=None, description="ts_utc of the last item from the previous page")):
    if start.tzinfo is None or end.tzinfo is None:
        raise HTTPException(status_code=400, detail={"error": "validation_failed", "message": "from/to need a timezone"})
    if end <= start:
        raise HTTPException(status_code=400, detail={"error": "validation_failed", "message": "'to' must be after 'from'"})
    items = repository.telemetry_range(vin.upper(), start, end, limit, cursor)
    next_cursor = items[-1]["ts_utc"].isoformat() if len(items) == limit else None
    return {"items": items, "next_cursor": next_cursor}


@app.get("/v1/vehicles/{vin}/alerts")
def alerts(vin: str, limit: int = Query(default=50, ge=1, le=500)):
    return {"items": repository.alerts_for_vin(vin.upper(), limit)}


@app.get("/v1/fleets/{customer_id}/latest")
def fleet_latest(customer_id: int):
    items = repository.fleet_latest(customer_id)
    return {"customer_id": customer_id, "count": len(items), "items": items}
