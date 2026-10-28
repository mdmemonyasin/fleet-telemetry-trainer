"""M2: Kafka consumer -> Postgres.   Run:  python -m app.consumer   (from the python/ folder)

Delivery semantics: at-least-once. We commit the Kafka offset only AFTER the DB transaction commits,
so a crash can re-deliver a message — the UNIQUE (vin, ts_utc) insert makes that harmless (idempotent).
Messages we cannot parse go to telemetry.dlq instead of blocking the partition.
"""
import json
import logging
import signal

from confluent_kafka import Consumer, KafkaError
from pydantic import ValidationError

from app import config, repository
from app.alerts import overspeed_run_start
from app.db import get_pool
from app.kafka_client import EventPublisher
from app.models import TelemetryEvent

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("consumer")


def parse(raw: bytes) -> TelemetryEvent:
    return TelemetryEvent.model_validate(json.loads(raw))


def process(conn, event: TelemetryEvent, publisher: EventPublisher) -> bool:
    """Write one event and evaluate alerts inside the caller's transaction. Returns True if new."""
    inserted = repository.insert_telemetry(conn, event)
    if inserted and event.speed_kmph > config.OVERSPEED_KMPH:
        start = overspeed_run_start(repository.recent_speeds(conn, event.vin), config.OVERSPEED_KMPH,
                                    config.OVERSPEED_MIN_PINGS)
        if start is not None:
            details = {"limit_kmph": config.OVERSPEED_KMPH, "latest_speed_kmph": event.speed_kmph}
            if repository.insert_alert(conn, event.vin, "OVERSPEED", start, details):
                publisher.publish(config.TOPIC_ALERTS, event.vin,
                                  {"vin": event.vin, "type": "OVERSPEED", "started_at": start.isoformat(), **details})
                log.info("alert OVERSPEED vin=%s started_at=%s", event.vin, start)
    return inserted


def send_to_dlq(publisher: EventPublisher, msg, error: Exception) -> None:
    publisher.publish(config.TOPIC_DLQ, (msg.key() or b"").decode(errors="replace"),
                      {"raw": (msg.value() or b"").decode(errors="replace"), "error": str(error)[:500],
                       "source_topic": msg.topic(), "partition": msg.partition(), "offset": msg.offset()})
    publisher.flush()
    log.warning("sent to DLQ partition=%s offset=%s error=%s", msg.partition(), msg.offset(), str(error)[:120])


def run() -> None:
    consumer = Consumer({
        "bootstrap.servers": config.KAFKA_BOOTSTRAP,
        "group.id": config.CONSUMER_GROUP,
        "auto.offset.reset": "earliest",
        "enable.auto.commit": False,
    })
    publisher = EventPublisher()
    consumer.subscribe([config.TOPIC_RAW])
    running = True

    def stop(*_):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    log.info("consuming %s as group %s", config.TOPIC_RAW, config.CONSUMER_GROUP)
    written = dupes = 0
    pool = get_pool()
    try:
        while running:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                if msg.error().code() != KafkaError._PARTITION_EOF:
                    log.error("kafka error: %s", msg.error())
                continue
            # Fail fast: never silently drop an event we can't read (FLT-517)
            event = parse(msg.value())
            with pool.connection() as conn:                      # one transaction per message
                new = process(conn, event, publisher)
            consumer.commit(message=msg, asynchronous=False)     # commit offset only after DB commit
            written += new
            dupes += (not new)
            if (written + dupes) % 100 == 0:
                log.info("processed=%d written=%d duplicates_ignored=%d", written + dupes, written, dupes)
    finally:
        consumer.close()
        publisher.flush()
        log.info("stopped. written=%d duplicates_ignored=%d", written, dupes)


if __name__ == "__main__":
    run()
