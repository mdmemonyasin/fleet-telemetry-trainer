package com.fleetbootcamp.telemetry.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fleetbootcamp.telemetry.kafka.EventParser.InvalidEventException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventParserTest {
    final EventParser parser = new EventParser(new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    @Test
    void parsesV1() {
        var e = parser.parse("""
                {"schema":"v1","vin":"1FAXXK7Y3R5A2B4C6","ts_utc":"2026-01-15T10:00:00Z","speed_kmph":50,
                 "odometer_km":100,"lat":12.9,"lon":77.5,"source_oem":"OEM-A"}""");
        assertThat(e.vin()).isEqualTo("1FAXXK7Y3R5A2B4C6");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsUnknownSchema() {
        assertThatThrownBy(() -> parser.parse("""
                {"schema":"v2","vin":"1FAXXK7Y3R5A2B4C6","ts_utc":"2026-01-15T10:00:00Z","speed":{"value":50}}"""))
                .isInstanceOf(InvalidEventException.class);
    }
}
