package com.fleetbootcamp.telemetry.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import org.springframework.stereotype.Component;

/** Parses and checks a telemetry.raw message. Anything we can't trust -> InvalidEventException -> DLQ. */
@Component
public class EventParser {

    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ObjectMapper mapper;

    public EventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public TelemetryEvent parse(String raw) {
        TelemetryEvent e;
        try {
            e = mapper.readValue(raw, TelemetryEvent.class);
        } catch (Exception ex) {
            throw new InvalidEventException("unparseable JSON: " + ex.getMessage(), ex);
        }
        if (!TelemetryEvent.SCHEMA_V1.equals(e.schema())) {
            throw new InvalidEventException("unsupported schema: " + e.schema(), null);
        }
        if (e.vin() == null || e.vin().length() != 17 || e.tsUtc() == null || e.speedKmph() == null
                || e.odometerKm() == null || e.lat() == null || e.lon() == null || e.sourceOem() == null) {
            throw new InvalidEventException("missing required field", null);
        }
        return e;
    }
}
