package com.auraboot.framework.iot.tsport.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.iot.tsport.controller.dto.AggregateQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryRequest;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.LatestQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.RangeQueryResponse;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimeSeriesQueryServiceTest {

    private TimeSeriesPort port;
    private TimeSeriesQueryService service;

    @BeforeEach
    void setUp() {
        port = mock(TimeSeriesPort.class);
        service = new TimeSeriesQueryService(Optional.of(port));
        // Pin a deterministic tenant context for every test; per-test
        // overrides happen with MetaContext.setContext where needed.
        MetaContext.setContext(7L, 0L, null, "test");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // ------------------------------------------------------------------ port-null fallback

    @Test
    void unavailable_when_port_null() {
        TimeSeriesQueryService s = new TimeSeriesQueryService(Optional.empty());
        assertTrue(!s.tsdbAvailable());
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> s.queryLatest("dev-1", List.of("temp"), 1, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.unavailable"),
                () -> "expected unavailable code, got: " + ex.getMessage());
    }

    // ------------------------------------------------------------------ queryLatest

    @Test
    void latest_happy_path() {
        Instant ts = Instant.parse("2026-05-28T10:00:00Z");
        TimeSeriesPoint p = new TimeSeriesPoint("dev-1", "temp", ts, 23.5, "GOOD");
        when(port.queryLatest(eq(7L), eq("dev-1"), eq(List.of("temp")), eq(1)))
                .thenReturn(List.of(p));
        LatestQueryResponse r = service.queryLatest("dev-1", List.of("temp"), 1, null);
        assertEquals("dev-1", r.deviceCode());
        assertEquals(1, r.points().size());
        assertEquals(23.5, r.points().get(0).value());
    }

    @Test
    void latest_rejects_blank_device_code() {
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryLatest("  ", List.of("t"), 1, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.device_code.required"));
        verify(port, never())
                .queryLatest(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void latest_rejects_empty_codes() {
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryLatest("dev-1", List.of(), 1, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.codes.required"));
    }

    @Test
    void latest_rejects_zero_limit() {
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryLatest("dev-1", List.of("t"), 0, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.limit.out_of_range"));
    }

    @Test
    void latest_rejects_over_max_limit() {
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryLatest("dev-1", List.of("t"), TimeSeriesQueryService.MAX_LATEST_LIMIT + 1, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.limit.out_of_range"));
    }

    @Test
    void latest_fails_when_tenant_missing() {
        MetaContext.clear();
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryLatest("dev-1", List.of("t"), 1, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.tenant.missing"));
    }

    // ------------------------------------------------------------------ queryRange

    @Test
    void range_happy_path_no_downsample() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        when(port.queryRange(eq(7L), any(QueryParams.Range.class))).thenReturn(List.of());
        RangeQueryResponse r = service.queryRange("dev-1", List.of("temp"), from, to, null, null);
        assertEquals("dev-1", r.deviceCode());
        assertNull(r.downsampleApplied());
    }

    @Test
    void range_downsample_parsed_and_passed() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        when(port.queryRange(eq(7L), any(QueryParams.Range.class))).thenReturn(List.of());
        RangeQueryResponse r =
                service.queryRange("dev-1", List.of("temp"), from, to, "PT5M", null);
        assertEquals("PT5M", r.downsampleApplied());
    }

    @Test
    void range_rejects_inverted_window() {
        Instant from = Instant.parse("2026-05-28T01:00:00Z");
        Instant to = Instant.parse("2026-05-28T00:00:00Z");
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryRange("dev-1", List.of("t"), from, to, null, null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.range.invalid"));
    }

    @Test
    void range_rejects_malformed_downsample() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryRange("dev-1", List.of("t"), from, to, "5 minutes", null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.downsample.invalid"));
    }

    // ------------------------------------------------------------------ queryAggregate

    @Test
    void aggregate_happy_path() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        Instant bucket = Instant.parse("2026-05-28T00:00:00Z");
        when(port.queryAggregate(eq(7L), any(QueryParams.Aggregate.class)))
                .thenReturn(List.of(new AggregatedPoint("dev-1", "temp", bucket, 22.0, 6L)));
        AggregateQueryResponse r = service.queryAggregate(
                "dev-1", List.of("temp"), from, to, QueryParams.Aggregation.AVG, "PT10M", null);
        assertEquals(QueryParams.Aggregation.AVG, r.aggregation());
        assertEquals("PT10M", r.groupBy());
        assertEquals(1, r.buckets().size());
        assertEquals(6L, r.buckets().get(0).pointCount());
    }

    @Test
    void aggregate_rejects_missing_groupBy() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryAggregate(
                        "dev-1", List.of("t"), from, to, QueryParams.Aggregation.AVG, " ", null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.groupby.required"));
    }

    @Test
    void aggregate_rejects_missing_aggregation() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryAggregate(
                        "dev-1", List.of("t"), from, to, null, "PT5M", null));
        assertTrue(ex.getMessage().startsWith("iot.tsport.aggregation.required"));
    }

    // ------------------------------------------------------------------ batchQuery

    @Test
    void batch_mixed_types_returns_aligned_results() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        when(port.queryLatest(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(port.queryRange(anyLong(), any())).thenReturn(List.of());
        BatchQueryRequest req = new BatchQueryRequest(List.of(
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.LATEST, "dev-1", List.of("t"),
                        1, null, null, null, null, null),
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.RANGE, "dev-2", List.of("t"),
                        null, from, to, null, null, null)));
        BatchQueryResponse r = service.batchQuery(req, null);
        assertEquals(2, r.results().size());
        assertEquals(BatchQueryRequest.QueryType.LATEST, r.results().get(0).type());
        assertEquals("dev-2", r.results().get(1).deviceCode());
        assertNull(r.results().get(0).errorCode());
    }

    @Test
    void batch_partial_success_one_invalid() {
        when(port.queryLatest(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        BatchQueryRequest req = new BatchQueryRequest(List.of(
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.LATEST, "dev-1", List.of("t"),
                        1, null, null, null, null, null),
                // limit out of range -> error, but does not abort sibling
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.LATEST, "dev-2", List.of("t"),
                        99999, null, null, null, null, null)));
        BatchQueryResponse r = service.batchQuery(req, null);
        assertNull(r.results().get(0).errorCode());
        assertEquals("iot.tsport.limit.out_of_range", r.results().get(1).errorCode());
    }

    @Test
    void batch_rejects_oversize() {
        // record canonical constructor rejects > 50
        List<BatchQueryRequest.Query> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(new BatchQueryRequest.Query(
                    BatchQueryRequest.QueryType.LATEST, "d" + i, List.of("c"),
                    1, null, null, null, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new BatchQueryRequest(tooMany));
    }

    @Test
    void batch_rejects_empty() {
        assertThrows(IllegalArgumentException.class, () -> new BatchQueryRequest(List.of()));
    }

    // ------------------------------------------------------------------ cross-tenant hook

    @Test
    void cross_tenant_override_is_used_when_supplied() {
        when(port.queryLatest(eq(42L), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        LatestQueryResponse r = service.queryLatest("dev-1", List.of("t"), 1, 42L);
        assertNotNull(r);
        verify(port).queryLatest(eq(42L), eq("dev-1"), eq(List.of("t")), eq(1));
    }

    @Test
    void cross_tenant_override_rejects_invalid() {
        MetaServiceException ex = assertThrows(
                MetaServiceException.class,
                () -> service.queryLatest("dev-1", List.of("t"), 1, 0L));
        assertTrue(ex.getMessage().startsWith("iot.tsport.cross_tenant.invalid"));
    }

    // ------------------------------------------------------------------ splitCodes helper

    @Test
    void splitCodes_trims_and_drops_blanks() {
        List<String> out = TimeSeriesQueryService.splitCodes("a, b ,, c");
        assertEquals(List.of("a", "b", "c"), out);
    }

    @Test
    void splitCodes_empty_input() {
        assertTrue(TimeSeriesQueryService.splitCodes(null).isEmpty());
        assertTrue(TimeSeriesQueryService.splitCodes(" ").isEmpty());
    }
}
