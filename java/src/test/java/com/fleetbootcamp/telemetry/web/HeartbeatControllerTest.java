package com.fleetbootcamp.telemetry.web;

import com.fleetbootcamp.telemetry.dto.TelemetryEvent;
import com.fleetbootcamp.telemetry.service.IngestService;
import com.fleetbootcamp.telemetry.service.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M1 API tests: Kafka is not needed — IngestService is mocked. */
@WebMvcTest(HeartbeatController.class)
class HeartbeatControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    IngestService ingest;

    static final String VALID = """
            {"vin":"1FAXXK7Y3R5A2B4C6","ts":"2026-01-15T10:00:00Z","speed_kmph":54.2,"odometer":1000.0,
             "odometer_unit":"km","lat":12.97,"lon":77.59,"heading":90,"soc_pct":null,"ignition":true,"source_oem":"OEM-A"}
            """;

    @BeforeEach
    void stub() {
        when(ingest.ingest(any())).thenAnswer(inv -> {
            TelemetryEvent e = Normalizer.normalize(inv.getArgument(0));
            return e;
        });
    }

    @Test
    void validHeartbeatAccepted() throws Exception {
        mvc.perform(post("/v1/heartbeats").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.vin").value("1FAXXK7Y3R5A2B4C6"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"vin\":\"1FAXXK7Y3R5A2B4C6\"|\"vin\":\"TOO-SHORT\"|vin",
            "\"vin\":\"1FAXXK7Y3R5A2B4C6\"|\"vin\":\"1HGCM82633A0O4352\"|vin",
            "\"speed_kmph\":54.2|\"speed_kmph\":-5|speed_kmph",
            "\"lat\":12.97|\"lat\":123.0|lat",
            "\"odometer_unit\":\"km\"|\"odometer_unit\":\"furlong\"|odometer_unit",
            "\"ts\":\"2026-01-15T10:00:00Z\"|\"ts\":\"27/10/2026 10:00\"|ts",
            "\"ts\":\"2026-01-15T10:00:00Z\"|\"ts\":\"2026-01-15T10:00:00\"|ts",
    })
    void invalidFieldReturns400(String from, String to, String field) throws Exception {
        mvc.perform(post("/v1/heartbeats").contentType(MediaType.APPLICATION_JSON).content(VALID.replace(from, to)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.details[0].field").value(field));
        verify(ingest, never()).ingest(any());
    }
}
