package com.fleetbootcamp.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * What a device (or OEM feed) sends to POST /v1/heartbeats.
 * OffsetDateTime means a timestamp WITHOUT a timezone fails to parse -> 400.
 */
public record HeartbeatRequest(
        @NotNull @Pattern(regexp = "^[A-HJ-NPR-Za-hj-npr-z0-9]{17}$",
                message = "must be 17 characters A-Z/0-9 excluding I, O, Q")
        @JsonProperty("vin") String vin,

        @NotNull
        @JsonProperty("ts") OffsetDateTime ts,

        @NotNull @DecimalMin("0") @DecimalMax("300")
        @JsonProperty("speed_kmph") Double speedKmph,

        @NotNull @PositiveOrZero
        @JsonProperty("odometer") Double odometer,

        @Pattern(regexp = "km|mi", message = "must be km or mi")
        @JsonProperty("odometer_unit") String odometerUnit,

        @NotNull @DecimalMin("-90") @DecimalMax("90")
        @JsonProperty("lat") Double lat,

        @NotNull @DecimalMin("-180") @DecimalMax("180")
        @JsonProperty("lon") Double lon,

        @Min(0) @Max(359)
        @JsonProperty("heading") Integer heading,

        @DecimalMin("0") @DecimalMax("100")
        @JsonProperty("soc_pct") Double socPct,

        @JsonProperty("ignition") Boolean ignition,

        @NotBlank @Size(max = 32)
        @JsonProperty("source_oem") String sourceOem
) {
}
