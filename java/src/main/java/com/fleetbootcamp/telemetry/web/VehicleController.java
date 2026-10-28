package com.fleetbootcamp.telemetry.web;

import com.fleetbootcamp.telemetry.dto.AlertView;
import com.fleetbootcamp.telemetry.dto.TelemetryView;
import com.fleetbootcamp.telemetry.error.ApiErrors.NotFoundException;
import com.fleetbootcamp.telemetry.error.ApiErrors.ValidationFailedException;
import com.fleetbootcamp.telemetry.repo.TelemetryRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** M3 query APIs. */
@RestController
@RequestMapping("/v1")
public class VehicleController {
    private final TelemetryRepository repo;

    public VehicleController(TelemetryRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/vehicles/{vin}/latest")
    public TelemetryView latest(@PathVariable String vin) {
        return repo.latest(vin.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("no telemetry for " + vin));
    }

    @GetMapping("/vehicles/{vin}/telemetry")
    public Map<String, Object> telemetry(
            @PathVariable String vin,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime cursor) {
        if (limit < 1 || limit > 1000) {
            throw new ValidationFailedException("limit", "limit must be 1..1000");
        }
        if (!to.isAfter(from)) {
            throw new ValidationFailedException("to", "'to' must be after 'from'");
        }
        List<TelemetryView> items = repo.range(vin.toUpperCase(Locale.ROOT), from, to, limit, cursor);
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("next_cursor", items.size() == limit ? items.get(items.size() - 1).tsUtc().toString() : null);
        return body;
    }

    @GetMapping("/vehicles/{vin}/alerts")
    public Map<String, List<AlertView>> alerts(@PathVariable String vin,
                                               @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Map.of("items", repo.alerts(vin.toUpperCase(Locale.ROOT), Math.min(Math.max(limit, 1), 500)));
    }

    @GetMapping("/fleets/{customerId}/latest")
    public Map<String, Object> fleetLatest(@PathVariable int customerId) {
        List<TelemetryView> items = repo.fleetLatest(customerId);
        return Map.of("customer_id", customerId, "count", items.size(), "items", items);
    }
}
