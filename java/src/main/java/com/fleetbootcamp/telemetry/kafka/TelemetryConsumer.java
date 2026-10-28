package com.fleetbootcamp.telemetry.kafka;

import com.fleetbootcamp.telemetry.Topics;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.service.TelemetryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * telemetry.raw -> Postgres.  At-least-once: ack-mode=record commits the offset only after this method returns,
 * i.e. after the DB transaction committed. A re-delivery is harmless because the insert is idempotent.
 * Exceptions go to the error handler in KafkaConfig (bad messages -> telemetry.dlq).
 */
@Component
@ConditionalOnProperty(name = "app.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class TelemetryConsumer {
    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final EventParser parser;
    private final TelemetryService service;
    private long processed;
    private long duplicates;

    public TelemetryConsumer(EventParser parser, TelemetryService service) {
        this.parser = parser;
        this.service = service;
    }

    @KafkaListener(topics = Topics.RAW)
    public void onMessage(ConsumerRecord<String, String> record) {
        TelemetryEvent event = parser.parse(record.value());
        boolean inserted = service.process(event);
        processed++;
        if (!inserted) {
            duplicates++;
        }
        if (processed % 100 == 0) {
            log.info("processed={} duplicates_ignored={}", processed, duplicates);
        }
    }
}
