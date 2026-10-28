package com.fleetbootcamp.telemetry.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Speed above the limit for N consecutive pings -> one alert per run, identified by the run's start time. */
public final class OverspeedRule {

    public record SpeedPoint(Instant ts, double speedKmph) {
    }

    private OverspeedRule() {
    }

    /**
     * @param newestFirst the vehicle's latest pings, ordered by ts DESC
     * @return start of the current overspeed run if it has at least {@code minPings} pings
     */
    public static Optional<Instant> runStart(List<SpeedPoint> newestFirst, double limitKmph, int minPings) {
        int run = 0;
        Instant start = null;
        for (SpeedPoint p : newestFirst) {
            if (p.speedKmph() > limitKmph) {
                run++;
                start = p.ts();
            } else {
                break;
            }
        }
        return run >= minPings ? Optional.of(start) : Optional.empty();
    }
}
