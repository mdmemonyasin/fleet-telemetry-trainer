package com.fleetbootcamp.telemetry.service;

import com.fleetbootcamp.telemetry.dto.HeartbeatRequest;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.service.OverspeedRule.SpeedPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizerAndRuleTest {
    static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    static HeartbeatRequest hb(double odometer, String unit, OffsetDateTime ts) {
        return new HeartbeatRequest("2gbxxk7y3r5a2b4c6", ts, 50.0, odometer, unit, 12.9, 77.5, 90, null, true, "OEM-B");
    }

    @Test
    void kmUnchanged_andVinUpperCased() {
        TelemetryEvent e = Normalizer.normalize(hb(100, "km", T0.atOffset(ZoneOffset.UTC)));
        assertThat(e.odometerKm()).isEqualTo(100.0);
        assertThat(e.vin()).isEqualTo("2GBXXK7Y3R5A2B4C6");
        assertThat(e.schema()).isEqualTo("v1");
    }

    @Test
    void milesConvertedOnce() {
        assertThat(Normalizer.normalize(hb(100, "mi", T0.atOffset(ZoneOffset.UTC))).odometerKm()).isEqualTo(160.9);
    }

    @Test
    void istTimestampStoredAsUtc() {
        OffsetDateTime ist = OffsetDateTime.parse("2026-01-15T15:30:00+05:30");
        assertThat(Normalizer.normalize(hb(1, "km", ist)).tsUtc()).isEqualTo(T0);
    }

    @Test
    void unknownUnitRejected() {
        assertThatThrownBy(() -> Normalizer.toKm(1, "furlong")).isInstanceOf(IllegalArgumentException.class);
    }

    /** speeds given oldest -> newest; returned newest-first like the DB query. */
    static List<SpeedPoint> rows(double... speeds) {
        List<SpeedPoint> r = new ArrayList<>();
        for (int i = 0; i < speeds.length; i++) {
            r.add(new SpeedPoint(T0.plusSeconds(5L * i), speeds[i]));
        }
        Collections.reverse(r);
        return r;
    }

    @Test
    void threeConsecutiveOverLimitTriggers() {
        assertThat(OverspeedRule.runStart(rows(60, 105, 110, 120), 100, 3)).contains(T0.plusSeconds(5));
    }

    @Test
    void twoOverLimitDoesNotTrigger() {
        assertThat(OverspeedRule.runStart(rows(60, 105, 110), 100, 3)).isEmpty();
    }

    @Test
    void exactlyLimitIsNotOverspeed() {
        assertThat(OverspeedRule.runStart(rows(100, 100, 100), 100, 3)).isEmpty();
    }

    @Test
    void runBrokenBySlowPingResets() {
        assertThat(OverspeedRule.runStart(rows(105, 110, 60, 120, 130), 100, 3)).isEmpty();
    }

    @Test
    void startStableWhileRunGrows() {
        Optional<Instant> a = OverspeedRule.runStart(rows(60, 105, 110, 120), 100, 3);
        Optional<Instant> b = OverspeedRule.runStart(rows(60, 105, 110, 120, 125), 100, 3);
        assertThat(a).isEqualTo(b);
    }
}
