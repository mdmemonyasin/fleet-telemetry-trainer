package com.fleetbootcamp.telemetry.service;

import com.fleetbootcamp.telemetry.dto.HeartbeatRequest;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;

import java.util.Locale;

/** Device heartbeat -> normalised TelemetryEvent (metric units, UTC). Pure functions: easy to unit-test. */
public final class Normalizer {
    public static final double KM_PER_MILE = 1.609344;

    private Normalizer() {
    }

    public static double toKm(double value, String unit) {
        if (unit == null || unit.equals("km")) {
            return value;
        }
        if (unit.equals("mi")) {
            return value * KM_PER_MILE;
        }
        throw new IllegalArgumentException("unknown odometer unit: " + unit);
    }

    public static TelemetryEvent normalize(HeartbeatRequest hb) {
        double odometer = hb.odometer();
        if ("OEM-B".equals(hb.sourceOem())) {
            // OEM-B feeds report miles -> convert to km (FLT-231)
            odometer = odometer * KM_PER_MILE;
        }
        return new TelemetryEvent(
                TelemetryEvent.SCHEMA_V1,
                hb.vin().trim().toUpperCase(Locale.ROOT),
                hb.ts().toInstant(),
                round(hb.speedKmph(), 2),
                round(toKm(odometer, hb.odometerUnit()), 1),
                hb.lat(),
                hb.lon(),
                hb.heading(),
                hb.socPct(),
                hb.ignition() == null ? Boolean.TRUE : hb.ignition(),
                hb.sourceOem());
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
