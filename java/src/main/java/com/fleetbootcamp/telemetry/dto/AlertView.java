package com.fleetbootcamp.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.OffsetDateTime;

public record AlertView(
        @JsonProperty("id") long id,
        @JsonProperty("vin") String vin,
        @JsonProperty("type") String type,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonRawValue @JsonProperty("details") String detailsJson,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}
