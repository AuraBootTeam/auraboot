package com.auraboot.framework.agent.service;

import com.auraboot.module.aps.dto.CalendarEntry;
import com.auraboot.module.aps.dto.ResourceInfo;
import com.auraboot.module.aps.dto.ScheduleJob;
import com.auraboot.module.aps.dto.ScheduleRequest;
import com.auraboot.module.aps.dto.ScheduleResult;
import com.auraboot.module.aps.dto.ScheduledOperation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter between PCBA dynamic-data rows (already fetched by
 * {@link ApsSchedulingService}) and the platform's
 * {@link com.auraboot.module.aps.engine.SchedulingEngine} DTOs.
 *
 * <p>Stateless on input but maintains internal id↔pid maps between
 * {@link #buildRequest} and {@link #toScheduleResultRows}; call them on the
 * same adapter instance within one schedule run.</p>
 *
 * <p>ID mapping: the engine uses {@code Long} keys; PCBA dynamic tables use
 * ULID {@code pid} strings. This adapter assigns synthetic sequential Long
 * ids and keeps reverse maps so engine output can be projected back into
 * the schedule_result row shape required by ApsSchedulingService.</p>
 */
public class PcbaApsAdapter {

    private static final BigDecimal DEFAULT_CAPACITY_PER_HOUR = new BigDecimal("10");
    private static final LocalTime DEFAULT_SHIFT_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_SHIFT_END = LocalTime.of(16, 0);

    /** Maps synthetic Long job id back to planned_order pid. */
    private final Map<Long, String> jobIdToPlannedOrderPid = new LinkedHashMap<>();
    /** Maps synthetic Long resource id back to resource pid. */
    private final Map<Long, String> resourceIdToPid = new LinkedHashMap<>();
    /** Maps synthetic Long resource id back to resource name. */
    private final Map<Long, String> resourceIdToName = new LinkedHashMap<>();
    /** Maps resource pid back to synthetic Long resource id (for calendar grouping). */
    private final Map<String, Long> resourcePidToId = new LinkedHashMap<>();

    /**
     * Build a {@link ScheduleRequest} for the platform scheduling engine.
     *
     * @param plannedOrderRows rows from mt_pe_planned_order (already filtered to
     *                         status IN (planned, firmed) and order_type = production)
     * @param resourceRows rows from mt_pe_resource (already filtered to status = active)
     * @param calendarsByResourcePid calendar rows pre-grouped by pe_rc_resource_id
     * @param horizonDays scheduling horizon (calendar entries outside this window are dropped)
     * @return populated ScheduleRequest, never null
     */
    public ScheduleRequest buildRequest(
            List<Map<String, Object>> plannedOrderRows,
            List<Map<String, Object>> resourceRows,
            Map<String, List<Map<String, Object>>> calendarsByResourcePid,
            int horizonDays) {
        clearMaps();

        List<ScheduleJob> jobs = mapJobs(plannedOrderRows);
        List<ResourceInfo> resources = mapResources(resourceRows);
        Map<Long, List<CalendarEntry>> calendars = mapCalendars(calendarsByResourcePid, horizonDays);

        LocalDateTime scheduleStart = LocalDate.now().atTime(DEFAULT_SHIFT_START);

        return ScheduleRequest.builder()
                .jobs(jobs)
                .resources(resources)
                .resourceCalendars(calendars)
                .setupTimes(new HashMap<>())
                .scheduleStart(scheduleStart)
                .build();
    }

    /**
     * Convert engine output back into row-shaped data ready for
     * {@code ApsSchedulingService.writeScheduleResult(...)} calls.
     *
     * <p>Each returned map contains: {@code workOrderId} (planned_order pid),
     * {@code operationName}, {@code resourcePid}, {@code resourceName},
     * {@code startTime}, {@code endTime}, {@code setupTimeMin},
     * {@code processingTimeMin}.</p>
     */
    public List<Map<String, Object>> toScheduleResultRows(ScheduleResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (result == null || result.getOperations() == null) return rows;
        for (ScheduledOperation op : result.getOperations()) {
            Map<String, Object> row = new HashMap<>();
            row.put("workOrderId", jobIdToPlannedOrderPid.get(op.getJobId()));
            row.put("operationName", op.getOperationName());
            row.put("resourcePid", resourceIdToPid.get(op.getResourceId()));
            row.put("resourceName", op.getResourceName() != null
                    ? op.getResourceName()
                    : resourceIdToName.get(op.getResourceId()));
            row.put("startTime", op.getStartTime());
            row.put("endTime", op.getEndTime());
            row.put("setupTimeMin", op.getSetupTimeMin());
            row.put("processingTimeMin", op.getProcessingTimeMin());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Build apply-schedule payload items keyed by the strings expected by
     * {@code ApplyScheduleHandler} (used when invoking via the PCBA command bus).
     */
    public List<Map<String, Object>> toApplyPayload(ScheduleResult result) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (result == null || result.getOperations() == null) return payload;
        for (ScheduledOperation op : result.getOperations()) {
            Map<String, Object> item = new HashMap<>();
            item.put("workOrderOpId", jobIdToPlannedOrderPid.get(op.getJobId()));
            item.put("operationName", op.getOperationName());
            item.put("resourceId", resourceIdToPid.get(op.getResourceId()));
            item.put("resourceName", op.getResourceName() != null
                    ? op.getResourceName()
                    : resourceIdToName.get(op.getResourceId()));
            item.put("startTime", op.getStartTime() != null ? op.getStartTime().toString() : null);
            item.put("endTime", op.getEndTime() != null ? op.getEndTime().toString() : null);
            item.put("setupTimeMin", op.getSetupTimeMin());
            item.put("processingTimeMin", op.getProcessingTimeMin());
            payload.add(item);
        }
        return payload;
    }

    // ---- internal mappers ----

    private List<ScheduleJob> mapJobs(List<Map<String, Object>> rows) {
        List<ScheduleJob> jobs = new ArrayList<>();
        if (rows == null) return jobs;
        long nextId = 1L;
        for (Map<String, Object> row : rows) {
            String pid = asString(row.get("pid"));
            if (pid == null) continue;

            long jobId = nextId++;
            jobIdToPlannedOrderPid.put(jobId, pid);

            BigDecimal qty = asDecimal(row.get("pe_plo_order_qty"), BigDecimal.ONE);
            int processingMin = qty
                    .multiply(new BigDecimal(60))
                    .divide(DEFAULT_CAPACITY_PER_HOUR, 0, RoundingMode.CEILING)
                    .intValue();

            String status = asString(row.get("pe_plo_status"));

            jobs.add(ScheduleJob.builder()
                    .id(jobId)
                    .code(pid)
                    .productName(asString(row.get("pe_plo_material_name")))
                    .operationName(asString(row.get("pe_plo_material_name")))
                    .processingTimeMin(processingMin)
                    .dueDate(asDateTime(row.get("pe_plo_need_date")))
                    .priority("firmed".equalsIgnoreCase(status) ? 1 : 5)
                    .arrivalTime(asDateTime(row.get("pe_plo_order_date")))
                    .build());
        }
        return jobs;
    }

    private List<ResourceInfo> mapResources(List<Map<String, Object>> rows) {
        List<ResourceInfo> resources = new ArrayList<>();
        if (rows == null) return resources;
        long nextId = 1L;
        for (Map<String, Object> row : rows) {
            String pid = asString(row.get("pid"));
            if (pid == null) continue;

            long resId = nextId++;
            String name = asString(row.get("pe_res_name"));
            resourceIdToPid.put(resId, pid);
            resourceIdToName.put(resId, name);
            resourcePidToId.put(pid, resId);

            resources.add(ResourceInfo.builder()
                    .id(resId)
                    .name(name)
                    .type(asString(row.get("pe_res_type")))
                    .capacityPerHour(asDecimal(row.get("pe_res_capacity_per_hour"), DEFAULT_CAPACITY_PER_HOUR))
                    .build());
        }
        return resources;
    }

    private Map<Long, List<CalendarEntry>> mapCalendars(
            Map<String, List<Map<String, Object>>> rowsByResourcePid, int horizonDays) {
        Map<Long, List<CalendarEntry>> result = new LinkedHashMap<>();
        if (rowsByResourcePid == null || rowsByResourcePid.isEmpty()) return result;
        if (resourcePidToId.isEmpty()) return result;

        LocalDate windowStart = LocalDate.now();
        LocalDate windowEnd = windowStart.plusDays(horizonDays);

        for (Map.Entry<String, List<Map<String, Object>>> e : rowsByResourcePid.entrySet()) {
            Long resourceLongId = resourcePidToId.get(e.getKey());
            if (resourceLongId == null) continue;
            List<CalendarEntry> entries = new ArrayList<>();
            for (Map<String, Object> row : e.getValue()) {
                LocalDate date = asDate(row.get("pe_rc_date"));
                if (date == null) continue;
                if (date.isBefore(windowStart) || date.isAfter(windowEnd)) continue;
                entries.add(CalendarEntry.builder()
                        .date(date)
                        .startTime(asTime(row.get("pe_rc_start_time"), DEFAULT_SHIFT_START))
                        .endTime(asTime(row.get("pe_rc_end_time"), DEFAULT_SHIFT_END))
                        .holiday(asBoolean(row.get("pe_rc_is_holiday")))
                        .build());
            }
            if (!entries.isEmpty()) {
                result.put(resourceLongId, entries);
            }
        }
        return result;
    }

    private void clearMaps() {
        jobIdToPlannedOrderPid.clear();
        resourceIdToPid.clear();
        resourceIdToName.clear();
        resourcePidToId.clear();
    }

    // ---- value parsers ----

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal asDecimal(Object v, BigDecimal fallback) {
        if (v == null) return fallback;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDate asDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof LocalDateTime ldt) return ldt.toLocalDate();
        String s = v.toString();
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime asDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof LocalDate ld) return ld.atStartOfDay();
        String s = v.toString().replace(' ', 'T');
        try {
            return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s);
        } catch (Exception e) {
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).atStartOfDay();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static LocalTime asTime(Object v, LocalTime fallback) {
        if (v == null) return fallback;
        if (v instanceof LocalTime lt) return lt;
        String s = v.toString();
        try {
            return LocalTime.parse(s.length() >= 5 ? s.substring(0, 5) : s, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            try {
                return LocalTime.parse(s);
            } catch (Exception e2) {
                return fallback;
            }
        }
    }

    private static boolean asBoolean(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }
}
