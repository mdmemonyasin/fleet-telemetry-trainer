package com.fleetbootcamp.telemetry.repo;

import com.fleetbootcamp.telemetry.dto.AlertView;
import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.dto.TelemetryView;
import com.fleetbootcamp.telemetry.service.OverspeedRule.SpeedPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** All SQL lives here. Always use ? placeholders — never concatenate input into SQL. */
@Repository
public class TelemetryRepository {
    private static final String COLS =
            "vin, ts_utc, speed_kmph, odometer_km, lat, lon, heading, soc_pct, ignition, source_oem";

    private final JdbcTemplate jdbc;

    public TelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a re-delivered (vin, ts_utc) is ignored. Returns true if a new row was written. */
    public boolean insert(TelemetryEvent e) {
        int n = jdbc.update(
                "INSERT INTO telemetry (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (vin, ts_utc) DO NOTHING",
                e.vin(), utc(e.tsUtc()), e.speedKmph(), e.odometerKm(), e.lat(), e.lon(),
                e.heading(), e.socPct(), e.ignition(), e.sourceOem());
        return n == 1;
    }

    public List<SpeedPoint> recentSpeeds(String vin, int limit) {
        return jdbc.query("SELECT ts_utc, speed_kmph FROM telemetry WHERE vin = ? ORDER BY ts_utc DESC LIMIT ?",
                (rs, i) -> new SpeedPoint(rs.getObject("ts_utc", OffsetDateTime.class).toInstant(),
                        rs.getDouble("speed_kmph")),
                vin, limit);
    }

    public boolean insertAlert(String vin, String type, Instant startedAt, String detailsJson) {
        int n = jdbc.update("INSERT INTO alerts (vin, type, started_at, details) VALUES (?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (vin, type, started_at) DO NOTHING", vin, type, utc(startedAt), detailsJson);
        return n == 1;
    }

    public Optional<TelemetryView> latest(String vin) {
        return jdbc.query("SELECT " + COLS + " FROM telemetry WHERE vin = ? ORDER BY ts_utc DESC LIMIT 1",
                VIEW, vin).stream().findFirst();
    }

    /** Keyset pagination on ts_utc (no OFFSET): stable under inserts and fast with the (vin, ts_utc) index. */
    public List<TelemetryView> range(String vin, OffsetDateTime from, OffsetDateTime to, int limit,
                                     OffsetDateTime after) {
        OffsetDateTime cursor = after != null ? after : OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        return jdbc.query("SELECT " + COLS + " FROM telemetry WHERE vin = ? AND ts_utc >= ? AND ts_utc < ? "
                + "AND ts_utc > ? ORDER BY ts_utc ASC LIMIT ?", VIEW, vin, from, to, cursor, limit);
    }

    public List<AlertView> alerts(String vin, int limit) {
        return jdbc.query("SELECT id, vin, type, started_at, details::text AS details, created_at FROM alerts "
                        + "WHERE vin = ? ORDER BY started_at DESC LIMIT ?",
                (rs, i) -> new AlertView(rs.getLong("id"), rs.getString("vin").trim(), rs.getString("type"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getString("details"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                vin, limit);
    }

    /** Latest row for every active vehicle of a customer. VIN match is case-insensitive (FLT-482). */
    public List<TelemetryView> fleetLatest(int customerId) {
        List<String> vins = jdbc.queryForList(
                "SELECT vin FROM vehicles WHERE customer_id = ? AND status = 'active'", String.class, customerId);
        List<TelemetryView> items = new java.util.ArrayList<>();
        for (String vin : vins) {
            items.addAll(jdbc.query("SELECT " + COLS + " FROM telemetry WHERE upper(vin) = upper(?) "
                    + "ORDER BY ts_utc DESC LIMIT 1", VIEW, vin));
        }
        return items;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static final RowMapper<TelemetryView> VIEW = TelemetryRepository::mapView;

    private static TelemetryView mapView(ResultSet rs, int i) throws SQLException {
        return new TelemetryView(
                rs.getString("vin").trim(),
                rs.getObject("ts_utc", OffsetDateTime.class),
                rs.getDouble("speed_kmph"),
                rs.getObject("odometer_km") == null ? null : rs.getDouble("odometer_km"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                (Integer) rs.getObject("heading"),
                rs.getObject("soc_pct") == null ? null : rs.getDouble("soc_pct"),
                (Boolean) rs.getObject("ignition"),
                rs.getString("source_oem"));
    }
}
