package com.auraboot.framework.iot.tsport.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc-level test for {@link TimeSeriesQueryController} covering the
 * REST edge: URL routing, parameter parsing, ApiResponse envelope, and
 * the controller-local {@code @ExceptionHandler} status mapping.
 *
 * <p>Spring Security / permission interception is intentionally NOT in the
 * scope here — the contract for {@code @RequirePermission} is unit-tested
 * by the permission aspect module. The {@link TimeSeriesQueryServiceTest}
 * covers business validation in depth.
 */
class TimeSeriesQueryControllerTest {

    private TimeSeriesPort port;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        port = mock(TimeSeriesPort.class);
        TimeSeriesQueryService service = new TimeSeriesQueryService(Optional.of(port));
        TimeSeriesQueryController controller = new TimeSeriesQueryController(service);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter conv = new MappingJackson2HttpMessageConverter(om);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(conv)
                .build();
        MetaContext.setContext(7L, 0L, null, "test");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void latest_endpoint_returns_success_envelope() throws Exception {
        TimeSeriesPoint p = new TimeSeriesPoint(
                "dev-1", "temp", Instant.parse("2026-05-28T10:00:00Z"), 23.5, "GOOD");
        when(port.queryLatest(eq(7L), eq("dev-1"), eq(List.of("temp", "humidity")), eq(2)))
                .thenReturn(List.of(p));

        mvc.perform(get("/iot/api/v1/timeseries/latest")
                        .param("deviceCode", "dev-1")
                        .param("codes", "temp,humidity")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceCode").value("dev-1"))
                .andExpect(jsonPath("$.data.points[0].code").value("temp"));
    }

    @Test
    void range_endpoint_parses_instants_and_downsample() throws Exception {
        when(port.queryRange(eq(7L), any())).thenReturn(List.of());
        mvc.perform(get("/iot/api/v1/timeseries/range")
                        .param("deviceCode", "dev-1")
                        .param("codes", "temp")
                        .param("from", "2026-05-28T00:00:00Z")
                        .param("to", "2026-05-28T01:00:00Z")
                        .param("downsample", "PT5M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downsampleApplied").value("PT5M"));
    }

    @Test
    void aggregate_endpoint_returns_success() throws Exception {
        when(port.queryAggregate(eq(7L), any())).thenReturn(List.of());
        mvc.perform(get("/iot/api/v1/timeseries/aggregate")
                        .param("deviceCode", "dev-1")
                        .param("codes", "temp")
                        .param("from", "2026-05-28T00:00:00Z")
                        .param("to", "2026-05-28T01:00:00Z")
                        .param("aggregation", "AVG")
                        .param("groupBy", "PT10M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aggregation").value("AVG"))
                .andExpect(jsonPath("$.data.groupBy").value("PT10M"));
    }

    @Test
    void batch_endpoint_round_trips_request() throws Exception {
        when(port.queryLatest(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        String body = """
                {
                  "queries": [
                    { "type": "LATEST", "deviceCode": "dev-1", "codes": ["temp"], "limit": 1 }
                  ]
                }
                """;
        mvc.perform(post("/iot/api/v1/timeseries/batchQuery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].type").value("LATEST"))
                .andExpect(jsonPath("$.data.results[0].deviceCode").value("dev-1"));
    }

    @Test
    void unavailable_returns_503() throws Exception {
        TimeSeriesQueryService s = new TimeSeriesQueryService(Optional.empty());
        TimeSeriesQueryController c = new TimeSeriesQueryController(s);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc m = MockMvcBuilders.standaloneSetup(c)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
        m.perform(get("/iot/api/v1/timeseries/latest")
                        .param("deviceCode", "dev-1")
                        .param("codes", "temp"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("iot.tsport.unavailable"));
    }

    @Test
    void blank_device_code_returns_400() throws Exception {
        mvc.perform(get("/iot/api/v1/timeseries/latest")
                        .param("deviceCode", "")
                        .param("codes", "temp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("iot.tsport.device_code.required"));
    }

    @Test
    void missing_tenant_returns_401() throws Exception {
        MetaContext.clear();
        mvc.perform(get("/iot/api/v1/timeseries/latest")
                        .param("deviceCode", "dev-1")
                        .param("codes", "temp"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("iot.tsport.tenant.missing"));
    }

    @Test
    void permission_annotation_present() {
        // Direct reflection guard so a future refactor cannot silently
        // drop @RequirePermission on the controller class.
        var ann = TimeSeriesQueryController.class.getAnnotation(
                com.auraboot.framework.permission.annotation.RequirePermission.class);
        assertEquals(
                "iot.data_point.read",
                ann.value(),
                "TimeSeriesQueryController must require iot.data_point.read");
    }
}
