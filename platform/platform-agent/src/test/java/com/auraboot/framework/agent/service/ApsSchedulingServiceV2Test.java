package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.module.aps.dto.ResourceInfo;
import com.auraboot.module.aps.dto.ScheduleConflict;
import com.auraboot.module.aps.dto.ScheduleRequest;
import com.auraboot.module.aps.dto.ScheduleResult;
import com.auraboot.module.aps.dto.ScheduleJob;
import com.auraboot.module.aps.dto.ScheduledOperation;
import com.auraboot.module.aps.engine.SchedulingEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ApsSchedulingService#runScheduleV2}.
 * Verifies the engine wiring + adapter round-trip + schedule_result writes.
 */
class ApsSchedulingServiceV2Test {

    private DynamicDataMapper mapper;
    private SchedulingEngine engine;
    private ApsSchedulingService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(DynamicDataMapper.class);
        engine = mock(SchedulingEngine.class);
        service = new ApsSchedulingService(mapper);
        // package-private wiring of the optional @Autowired field
        Field f = ApsSchedulingService.class.getDeclaredField("schedulingEngine");
        f.setAccessible(true);
        f.set(service, engine);

        // stub tableExists by stubbing the count query (returns >0 means exists)
        when(mapper.checkTableExistsWithoutTenant(anyString())).thenReturn(1);
    }

    @Test
    void runScheduleV2_returnsEarlyWhenEngineIsNull() throws Exception {
        Field f = ApsSchedulingService.class.getDeclaredField("schedulingEngine");
        f.setAccessible(true);
        f.set(service, null);

        // When engine is null, falls back to V1 path. We stub V1's first step to short-circuit
        // by returning an empty list of planned orders.
        when(mapper.selectByQueryWithoutTenant(anyString(), any())).thenReturn(List.of());

        Map<String, Object> result = service.runScheduleV2(1L, 7, "forwardFifo");
        assertEquals(0, result.get("scheduledCount"));
    }

    @Test
    void runScheduleV2_returnsEarlyWhenNoPlannedOrders() {
        when(mapper.selectByQueryWithoutTenant(anyString(), any())).thenReturn(List.of());

        Map<String, Object> result = service.runScheduleV2(1L, 7, "forwardFifo");
        assertEquals(0, result.get("scheduledCount"));
        assertEquals(0, result.get("conflictCount"));
        assertEquals("forwardFifo", result.get("strategy"));
        verify(engine, never()).schedule(any(), anyString());
    }

    @Test
    void runScheduleV2_returnsEarlyWhenNoActiveResources() {
        // 1st call returns planned orders, 2nd call (resources) returns empty
        when(mapper.selectByQueryWithoutTenant(anyString(), any()))
                .thenReturn(List.of(plannedOrder("PO-1")))
                .thenReturn(List.of());

        Map<String, Object> result = service.runScheduleV2(1L, 7, "forwardFifo");
        assertEquals(0, result.get("scheduledCount"));
        assertEquals(1, result.get("conflictCount"));
        verify(engine, never()).schedule(any(), anyString());
    }

    @Test
    void runScheduleV2_unknownStrategy_returnsErrorWithoutWriting() {
        when(mapper.selectByQueryWithoutTenant(anyString(), any()))
                .thenReturn(List.of(plannedOrder("PO-1")))   // orders
                .thenReturn(List.of(resource("RES-1")))      // resources
                .thenReturn(List.of());                       // calendars

        when(engine.schedule(any(), eq("bogus")))
                .thenThrow(new IllegalArgumentException("Unknown strategy: bogus"));

        Map<String, Object> result = service.runScheduleV2(1L, 7, "bogus");
        assertEquals(0, result.get("scheduledCount"));
        assertEquals("bogus", result.get("strategy"));
        assertTrue(result.get("message").toString().toLowerCase().contains("unknown"));
        verify(mapper, never()).insert(eq("mt_pe_schedule_result"), any());
    }

    @Test
    void runScheduleV2_happyPath_writesScheduleResultsToTable() {
        when(mapper.selectByQueryWithoutTenant(anyString(), any()))
                .thenReturn(List.of(plannedOrder("PO-1"), plannedOrder("PO-2")))  // orders
                .thenReturn(List.of(resource("RES-1")))                            // resources
                .thenReturn(List.of());                                             // calendars

        // Capture what adapter built and return a fake engine result that references those job ids
        ArgumentCaptor<ScheduleRequest> reqCap = ArgumentCaptor.forClass(ScheduleRequest.class);
        when(engine.schedule(reqCap.capture(), eq("forwardFifo")))
                .thenAnswer(inv -> fakeResult(reqCap.getValue()));

        Map<String, Object> result = service.runScheduleV2(1L, 7, "forwardFifo");

        assertEquals(2, result.get("scheduledCount"));
        assertEquals(0, result.get("conflictCount"));
        assertEquals("forwardFifo", result.get("strategy"));

        // Verify both schedule_result rows were inserted with the original planned_order pids
        ArgumentCaptor<Map<String, Object>> rowCap = ArgumentCaptor.forClass(Map.class);
        verify(mapper, times(2)).insert(eq("mt_pe_schedule_result"), rowCap.capture());
        List<Map<String, Object>> insertedRows = rowCap.getAllValues();
        assertEquals("PO-1", insertedRows.get(0).get("pe_sched_work_order_id"));
        assertEquals("PO-2", insertedRows.get(1).get("pe_sched_work_order_id"));
        assertEquals("RES-1", insertedRows.get(0).get("pe_sched_resource_id"));
    }

    @Test
    void runScheduleV2_conflictsAreCounted() {
        when(mapper.selectByQueryWithoutTenant(anyString(), any()))
                .thenReturn(List.of(plannedOrder("PO-1")))
                .thenReturn(List.of(resource("RES-1")))
                .thenReturn(List.of());

        ScheduleResult r = ScheduleResult.builder()
                .strategy("forwardFifo")
                .operations(List.of())
                .conflicts(List.of(
                        ScheduleConflict.builder().reason("no slot").build(),
                        ScheduleConflict.builder().reason("no skill").build()
                ))
                .build();
        when(engine.schedule(any(), eq("forwardFifo"))).thenReturn(r);

        Map<String, Object> result = service.runScheduleV2(1L, 7, "forwardFifo");
        assertEquals(0, result.get("scheduledCount"));
        assertEquals(2, result.get("conflictCount"));
    }

    // ---- helpers ----

    private static ScheduleResult fakeResult(ScheduleRequest request) {
        List<ResourceInfo> resources = request.getResources();
        Long resId = resources.isEmpty() ? null : resources.get(0).getId();
        List<ScheduledOperation> ops = request.getJobs().stream()
                .map(job -> ScheduledOperation.builder()
                        .jobId(job.getId())
                        .operationName(job.getOperationName())
                        .resourceId(resId)
                        .startTime(LocalDateTime.of(2026, 5, 28, 8, 0))
                        .endTime(LocalDateTime.of(2026, 5, 28, 10, 0))
                        .setupTimeMin(30)
                        .processingTimeMin(120)
                        .build())
                .toList();
        return ScheduleResult.builder()
                .strategy("forwardFifo")
                .operations(ops)
                .build();
    }

    private static Map<String, Object> plannedOrder(String pid) {
        Map<String, Object> row = new HashMap<>();
        row.put("pid", pid);
        row.put("pe_plo_status", "planned");
        row.put("pe_plo_order_type", "production");
        row.put("pe_plo_material_name", "Material-" + pid);
        row.put("pe_plo_order_qty", "1");
        row.put("pe_plo_need_date", LocalDate.now().plusDays(3).toString());
        row.put("pe_plo_order_date", LocalDate.now().toString());
        return row;
    }

    private static Map<String, Object> resource(String pid) {
        Map<String, Object> row = new HashMap<>();
        row.put("pid", pid);
        row.put("pe_res_name", "Resource-" + pid);
        row.put("pe_res_type", "machine");
        row.put("pe_res_status", "active");
        row.put("pe_res_capacity_per_hour", "10");
        return row;
    }
}
