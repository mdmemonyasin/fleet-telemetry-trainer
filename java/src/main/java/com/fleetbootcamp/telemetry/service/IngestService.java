package com.fleetbootcamp.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetbootcamp.telemetry.Topics;
import com.fleetbootcamp.telemetry.dto.HeartbeatRequest;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.error.ApiErrors.ValidationFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class IngestService {
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public IngestService(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this(kafka, mapper, Clock.systemUTC());
    }

    IngestService(KafkaTemplate<String, String> kafka, ObjectMapper mapper, Clock clock) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Validate business rules, normalise, publish keyed by VIN (keeps one vehicle's events ordered). */
    public TelemetryEvent ingest(HeartbeatRequest hb) {
        if (hb.ts().toInstant().isAfter(Instant.now(clock).plus(MAX_CLOCK_SKEW))) {
            throw new ValidationFailedException("ts", "ts is in the future");
        }
        TelemetryEvent event = Normalizer.normalize(hb);
        try {
            kafka.send(Topics.RAW, event.vin(), mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise event", e);
        }
        return event;
    }
}
