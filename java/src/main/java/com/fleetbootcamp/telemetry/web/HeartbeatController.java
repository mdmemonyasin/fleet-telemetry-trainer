package com.fleetbootcamp.telemetry.web;

import com.fleetbootcamp.telemetry.dto.HeartbeatRequest;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.service.IngestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class HeartbeatController {
    private final IngestService ingest;

    public HeartbeatController(IngestService ingest) {
        this.ingest = ingest;
    }

    /** M1: validate -> normalise -> publish to Kafka. 202 = accepted for processing (not yet stored). */
    @PostMapping("/heartbeats")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody HeartbeatRequest request) {
        TelemetryEvent e = ingest.ingest(request);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "vin", e.vin(), "ts_utc", e.tsUtc().toString()));
    }
}
