package com.fleetbootcamp.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetbootcamp.telemetry.Topics;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.repo.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class TelemetryService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final double limitKmph;
    private final int minPings;

    public TelemetryService(TelemetryRepository repo, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                            @Value("${app.overspeed-kmph}") double limitKmph,
                            @Value("${app.overspeed-min-pings}") int minPings) {
        this.repo = repo;
        this.kafka = kafka;
        this.mapper = mapper;
        this.limitKmph = limitKmph;
        this.minPings = minPings;
    }

    /** Write one event and evaluate alerts in ONE DB transaction. Returns true if the row was new. */
    @Transactional
    public boolean process(TelemetryEvent e) {
        boolean inserted = repo.insert(e);
        if (inserted && e.speedKmph() > limitKmph) {
            Optional<Instant> start = OverspeedRule.runStart(repo.recentSpeeds(e.vin(), 50), limitKmph, minPings);
            if (start.isPresent()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("limit_kmph", limitKmph);
                details.put("latest_speed_kmph", e.speedKmph());
                if (repo.insertAlert(e.vin(), "OVERSPEED", start.get(), json(details))) {
                    Map<String, Object> msg = new LinkedHashMap<>(details);
                    msg.put("vin", e.vin());
                    msg.put("type", "OVERSPEED");
                    msg.put("started_at", start.get().toString());
                    kafka.send(Topics.ALERTS, e.vin(), json(msg));
                    log.info("alert OVERSPEED vin={} started_at={}", e.vin(), start.get());
                }
            }
        }
        return inserted;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
