package com.auraboot.framework.agent.service;

import com.auraboot.module.aps.dto.CalendarEntry;
import com.auraboot.module.aps.dto.ResourceInfo;
import com.auraboot.module.aps.dto.ScheduleJob;
import com.auraboot.module.aps.dto.ScheduleRequest;
import com.auraboot.module.aps.dto.ScheduleResult;
import com.auraboot.module.aps.dto.ScheduledOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PcbaApsAdapter} — input mapping (rows → ScheduleRequest)
 * and output mapping (ScheduleResult → schedule_result row data and apply payload).
 */
class PcbaApsAdapterTest {

    private PcbaApsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PcbaApsAdapter();
    }

    @Test
    void buildRequest_emptyInputs_returnsEmptyLists() {
        ScheduleRequest req = adapter.buildRequest(List.of(), List.of(), Map.of(), 7);
        assertNotNull(req);
        assertTrue(req.getJobs().isEmpty());
        assertTrue(req.getResources().isEmpty());
        assertTrue(req.getResourceCalendars().isEmpty());
        assertNotNull(req.getScheduleStart());
    }

    @Test
    void buildRequest_nullInputs_returnsEmptyLists() {
        ScheduleRequest req = adapter.buildRequest(null, null, null, 7);
        assertTrue(req.getJobs().isEmpty());
        assertTrue(req.getResources().isEmpty());
        assertTrue(req.getResourceCalendars().isEmpty());
    }

    @Test
    void buildRequest_mapsPlannedOrderFields() {
        List<Map<String, Object>> orders = List.of(
                plannedOrder("PO-A", "Widget", "5", "planned"));
        ScheduleRequest req = adapter.buildRequest(orders, List.of(), Map.of(), 7);

        assertEquals(1, req.getJobs().size());
        ScheduleJob j = req.getJobs().get(0);
        assertEquals("PO-A", j.getCode());
        assertEquals("Widget", j.getProductName());
        assertEquals("Widget", j.getOperationName());
        // qty=5, default capacity 10/h → 30 min
        assertEquals(30, j.getProcessingTimeMin());
    }

    @Test
    void buildRequest_firmedHasHigherPriority() {
        Map<String, Object> planned = plannedOrder("A", "X", "1", "planned");
        Map<String, Object> firmed = plannedOrder("B", "Y", "1", "firmed");
        ScheduleRequest req = adapter.buildRequest(List.of(planned, firmed), List.of(), Map.of(), 7);
        Map<String, Integer> priorityByCode = new HashMap<>();
        for (ScheduleJob j : req.getJobs()) priorityByCode.put(j.getCode(), j.getPriority());
        assertTrue(priorityByCode.get("B") < priorityByCode.get("A"),
                "firmed should have numerically smaller priority value (= higher rank)");
    }

    @Test
    void buildRequest_skipsRowsWithoutPid() {
        Map<String, Object> noPid = new HashMap<>(plannedOrder("ignored", "X", "1", "planned"));
        noPid.remove("pid");
        Map<String, Object> withPid = plannedOrder("PO-OK", "X", "1", "planned");
        ScheduleRequest req = adapter.buildRequest(List.of(noPid, withPid), List.of(), Map.of(), 7);
        assertEquals(1, req.getJobs().size());
        assertEquals("PO-OK", req.getJobs().get(0).getCode());
    }

    @Test
    void buildRequest_mapsResourceFields() {
        Map<String, Object> res = resource("RES-1", "Line A", "machine", "12");
        ScheduleRequest req = adapter.buildRequest(List.of(), List.of(res), Map.of(), 7);
        assertEquals(1, req.getResources().size());
        ResourceInfo r = req.getResources().get(0);
        assertEquals("Line A", r.getName());
        assertEquals("machine", r.getType());
        assertEquals(new BigDecimal("12"), r.getCapacityPerHour());
    }

    @Test
    void buildRequest_groupsCalendarsByResourceAndDropsOutOfWindowAndUnknown() {
        Map<String, Object> res = resource("RES-1", "Line A", "machine", "10");
        LocalDate today = LocalDate.now();
        Map<String, List<Map<String, Object>>> calendars = new LinkedHashMap<>();
        calendars.put("RES-1", List.of(
                calendar(today.toString(), "08:00", "16:00", false),
                calendar(today.plusDays(2).toString(), "08:00", "16:00", false),
                calendar(today.plusDays(30).toString(), "08:00", "16:00", false) // out of 7-day window
        ));
        calendars.put("RES-UNKNOWN", List.of(
                calendar(today.toString(), "08:00", "16:00", false)
        ));

        ScheduleRequest req = adapter.buildRequest(List.of(), List.of(res), calendars, 7);
        assertEquals(1, req.getResourceCalendars().size(),
                "unknown resource pid should be dropped");
        List<CalendarEntry> entries = req.getResourceCalendars().values().iterator().next();
        assertEquals(2, entries.size(), "out-of-window entry should be dropped");
        assertEquals(today, entries.get(0).getDate());
        assertEquals(LocalTime.of(8, 0), entries.get(0).getStartTime());
        assertEquals(LocalTime.of(16, 0), entries.get(0).getEndTime());
    }

    @Test
    void toScheduleResultRows_emptyResult_returnsEmpty() {
        assertTrue(adapter.toScheduleResultRows(null).isEmpty());
        assertTrue(adapter.toScheduleResultRows(
                ScheduleResult.builder().operations(new ArrayList<>()).build()).isEmpty());
    }

    @Test
    void toScheduleResultRows_preservesPidsViaInternalMaps() {
        // First build a request so adapter learns id↔pid maps
        Map<String, Object> order = plannedOrder("PO-XYZ", "Gear", "5", "planned");
        Map<String, Object> res = resource("RES-ABC", "CNC-01", "machine", "10");
        ScheduleRequest req = adapter.buildRequest(List.of(order), List.of(res), Map.of(), 7);
        Long jobId = req.getJobs().get(0).getId();
        Long resId = req.getResources().get(0).getId();

        ScheduledOperation op = ScheduledOperation.builder()
                .jobId(jobId)
                .operationName("Gear")
                .resourceId(resId)
                .startTime(LocalDateTime.of(2026, 5, 28, 8, 0))
                .endTime(LocalDateTime.of(2026, 5, 28, 10, 0))
                .setupTimeMin(30)
                .processingTimeMin(120)
                .build();
        ScheduleResult result = ScheduleResult.builder()
                .strategy("FORWARD_FIFO")
                .operations(List.of(op))
                .build();

        List<Map<String, Object>> rows = adapter.toScheduleResultRows(result);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("PO-XYZ", row.get("workOrderId"));
        assertEquals("RES-ABC", row.get("resourcePid"));
        assertEquals("CNC-01", row.get("resourceName"),
                "resourceName should be filled from adapter's internal name map when ScheduledOperation.resourceName is null");
        assertEquals(30, row.get("setupTimeMin"));
        assertEquals(120, row.get("processingTimeMin"));
        assertEquals(LocalDateTime.of(2026, 5, 28, 8, 0), row.get("startTime"));
        assertEquals(LocalDateTime.of(2026, 5, 28, 10, 0), row.get("endTime"));
    }

    @Test
    void toApplyPayload_returnsHandlerExpectedShape() {
        Map<String, Object> order = plannedOrder("PO-1", "Widget", "1", "planned");
        Map<String, Object> res = resource("RES-1", "Line", "machine", "10");
        ScheduleRequest req = adapter.buildRequest(List.of(order), List.of(res), Map.of(), 7);
        Long jobId = req.getJobs().get(0).getId();
        Long resId = req.getResources().get(0).getId();

        ScheduledOperation op = ScheduledOperation.builder()
                .jobId(jobId)
                .resourceId(resId)
                .operationName("Widget")
                .resourceName("Line")
                .startTime(LocalDateTime.of(2026, 5, 28, 8, 0))
                .endTime(LocalDateTime.of(2026, 5, 28, 9, 0))
                .setupTimeMin(15)
                .processingTimeMin(45)
                .build();
        ScheduleResult result = ScheduleResult.builder().operations(List.of(op)).build();

        List<Map<String, Object>> payload = adapter.toApplyPayload(result);
        Map<String, Object> item = payload.get(0);
        // Field names must match ApplyScheduleHandler.execute() lookups exactly
        assertEquals("PO-1", item.get("workOrderOpId"));
        assertEquals("Widget", item.get("operationName"));
        assertEquals("RES-1", item.get("resourceId"));
        assertEquals("Line", item.get("resourceName"));
        assertEquals("2026-05-28T08:00", item.get("startTime"));
        assertEquals("2026-05-28T09:00", item.get("endTime"));
        assertEquals(15, item.get("setupTimeMin"));
        assertEquals(45, item.get("processingTimeMin"));
    }

    @Test
    void buildRequest_secondCallResetsIdMaps() {
        Map<String, Object> first = plannedOrder("PO-FIRST", "X", "1", "planned");
        adapter.buildRequest(List.of(first), List.of(), Map.of(), 7);

        Map<String, Object> second = plannedOrder("PO-SECOND", "Y", "2", "planned");
        ScheduleRequest req2 = adapter.buildRequest(List.of(second), List.of(), Map.of(), 7);
        Long secondJobId = req2.getJobs().get(0).getId();

        ScheduledOperation op = ScheduledOperation.builder()
                .jobId(secondJobId)
                .operationName("Y")
                .build();
        ScheduleResult r = ScheduleResult.builder().operations(List.of(op)).build();

        // After reset, jobId=1 must map to PO-SECOND, not PO-FIRST
        assertEquals("PO-SECOND", adapter.toApplyPayload(r).get(0).get("workOrderOpId"));
    }

    // ---- fixtures ----

    private Map<String, Object> plannedOrder(String pid, String materialName, String qty, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("pid", pid);
        row.put("pe_plo_status", status);
        row.put("pe_plo_order_type", "production");
        row.put("pe_plo_material_name", materialName);
        row.put("pe_plo_order_qty", qty);
        row.put("pe_plo_need_date", LocalDate.now().plusDays(7).toString());
        row.put("pe_plo_order_date", LocalDate.now().toString());
        return row;
    }

    private Map<String, Object> resource(String pid, String name, String type, String capacity) {
        Map<String, Object> row = new HashMap<>();
        row.put("pid", pid);
        row.put("pe_res_name", name);
        row.put("pe_res_type", type);
        row.put("pe_res_status", "active");
        row.put("pe_res_capacity_per_hour", capacity);
        return row;
    }

    private Map<String, Object> calendar(String date, String start, String end, boolean holiday) {
        Map<String, Object> row = new HashMap<>();
        row.put("pe_rc_date", date);
        row.put("pe_rc_start_time", start);
        row.put("pe_rc_end_time", end);
        row.put("pe_rc_is_holiday", holiday);
        return row;
    }
}
