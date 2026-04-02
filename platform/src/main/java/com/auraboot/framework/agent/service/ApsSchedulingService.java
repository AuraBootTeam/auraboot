package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rule-based APS (Advanced Planning & Scheduling) service for PCBA manufacturing.
 * Implements forward scheduling: assigns planned orders to earliest available resource slots.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApsSchedulingService {

    private final DynamicDataMapper dynamicDataMapper;

    private static final String TABLE_PLANNED_ORDER = "mt_pe_planned_order";
    private static final String TABLE_RESOURCE = "mt_pe_resource";
    private static final String TABLE_RESOURCE_CALENDAR = "mt_pe_resource_calendar";
    private static final String TABLE_SCHEDULE_RESULT = "mt_pe_schedule_result";

    private static final BigDecimal DEFAULT_CAPACITY_PER_HOUR = new BigDecimal("10.00");
    private static final BigDecimal DEFAULT_AVAILABLE_HOURS = new BigDecimal("8.00");
    private static final int DEFAULT_SETUP_TIME_MIN = 30;

    /**
     * Run forward scheduling for the given tenant.
     *
     * @param tenantId tenant ID
     * @param horizonDays scheduling horizon in days from today
     * @return map with "scheduledCount" and "conflictCount"
     */
    public Map<String, Object> runSchedule(Long tenantId, int horizonDays) {
        // Check if tables exist
        if (!tableExists(TABLE_PLANNED_ORDER) || !tableExists(TABLE_RESOURCE)) {
            log.info("APS tables not found (plugin not imported). Returning 0.");
            return Map.of("scheduledCount", 0, "conflictCount", 0, "message", "Plugin tables not found");
        }

        // 1. Fetch planned orders (PLANNED or FIRMED), sorted by need_date ASC
        List<Map<String, Object>> plannedOrders = fetchPlannedOrders(tenantId);
        if (plannedOrders.isEmpty()) {
            log.info("No planned orders to schedule for tenant {}", tenantId);
            return Map.of("scheduledCount", 0, "conflictCount", 0, "message", "No planned orders found");
        }
        log.info("Found {} planned orders to schedule for tenant {}", plannedOrders.size(), tenantId);

        // 2. Fetch active resources
        List<Map<String, Object>> resources = fetchResources(tenantId);
        if (resources.isEmpty()) {
            log.warn("No active resources found for tenant {}", tenantId);
            return Map.of("scheduledCount", 0, "conflictCount", plannedOrders.size(),
                    "message", "No active resources available");
        }
        log.info("Found {} active resources for tenant {}", resources.size(), tenantId);

        // 3. Fetch resource calendars for the scheduling horizon
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(horizonDays);
        Map<String, List<Map<String, Object>>> calendarByResource = fetchCalendars(tenantId, startDate, endDate);

        // 4. Build resource availability tracker
        Map<String, ResourceTracker> trackers = new LinkedHashMap<>();
        for (Map<String, Object> resource : resources) {
            String resPid = getString(resource, "pid");
            String resName = getString(resource, "pe_res_name");
            BigDecimal capacityPerHour = getDecimal(resource, "pe_res_capacity_per_hour", DEFAULT_CAPACITY_PER_HOUR);
            List<Map<String, Object>> calendar = calendarByResource.getOrDefault(resPid, Collections.emptyList());
            trackers.put(resPid, new ResourceTracker(resPid, resName, capacityPerHour, calendar, startDate, endDate));
        }

        // 5. Forward schedule: assign each order to the earliest available resource slot
        int scheduledCount = 0;
        int conflictCount = 0;
        int scheduleVersion = generateScheduleVersion();

        for (Map<String, Object> order : plannedOrders) {
            String orderPid = getString(order, "pid");
            BigDecimal orderQty = getDecimal(order, "pe_plo_order_qty", BigDecimal.ONE);
            String materialName = getString(order, "pe_plo_material_name");

            // Find best resource (earliest finish time)
            ResourceTracker bestTracker = null;
            LocalDateTime bestStart = null;
            LocalDateTime bestEnd = null;
            int bestProcessingMin = 0;

            for (ResourceTracker tracker : trackers.values()) {
                int processingMinutes = calculateProcessingTime(orderQty, tracker.capacityPerHour);
                int totalMinutes = DEFAULT_SETUP_TIME_MIN + processingMinutes;
                LocalDateTime[] slot = tracker.findEarliestSlot(totalMinutes);

                if (slot != null) {
                    if (bestEnd == null || slot[1].isBefore(bestEnd)) {
                        bestTracker = tracker;
                        bestStart = slot[0];
                        bestEnd = slot[1];
                        bestProcessingMin = processingMinutes;
                    }
                }
            }

            if (bestTracker != null) {
                // Reserve the slot
                bestTracker.reserveSlot(bestStart, bestEnd);

                // Write schedule result
                writeScheduleResult(tenantId, orderPid, materialName,
                        bestTracker.resourcePid, bestTracker.resourceName,
                        bestStart, bestEnd, DEFAULT_SETUP_TIME_MIN, bestProcessingMin,
                        scheduleVersion);
                scheduledCount++;
            } else {
                log.warn("No available resource slot for planned order {} (qty={})", orderPid, orderQty);
                conflictCount++;
            }
        }

        log.info("APS scheduling complete: scheduled={}, conflicts={}, version={}",
                scheduledCount, conflictCount, scheduleVersion);
        return Map.of(
                "scheduledCount", scheduledCount,
                "conflictCount", conflictCount,
                "scheduleVersion", scheduleVersion,
                "totalOrders", plannedOrders.size(),
                "resourceCount", resources.size(),
                "message", String.format("Scheduled %d/%d orders across %d resources",
                        scheduledCount, plannedOrders.size(), resources.size())
        );
    }

    /**
     * Clear all SCHEDULED results for the tenant (to allow re-scheduling).
     *
     * @param tenantId tenant ID
     * @return map with "clearedCount"
     */
    public Map<String, Object> clearSchedule(Long tenantId) {
        if (!tableExists(TABLE_SCHEDULE_RESULT)) {
            return Map.of("clearedCount", 0, "message", "Schedule result table not found");
        }

        String countSql = "SELECT COUNT(*) AS cnt FROM " + TABLE_SCHEDULE_RESULT
                + " WHERE tenant_id = #{params.tenantId} AND pe_sched_status = 'scheduled'";
        Map<String, Object> params = Map.of("tenantId", tenantId);
        List<Map<String, Object>> countResult = dynamicDataMapper.selectByQueryWithoutTenant(countSql, params);
        long count = 0;
        if (countResult != null && !countResult.isEmpty()) {
            Object cnt = countResult.get(0).get("cnt");
            count = cnt instanceof Number ? ((Number) cnt).longValue() : 0;
        }

        if (count > 0) {
            String deleteSql = "DELETE FROM " + TABLE_SCHEDULE_RESULT
                    + " WHERE tenant_id = #{params.tenantId} AND pe_sched_status = 'scheduled'";
            dynamicDataMapper.deleteByQuery(deleteSql, params);
        }

        log.info("Cleared {} scheduled results for tenant {}", count, tenantId);
        return Map.of("clearedCount", count, "message", String.format("Cleared %d scheduled results", count));
    }

    // ==================== Private helpers ====================

    private boolean tableExists(String tableName) {
        try {
            return dynamicDataMapper.checkTableExistsWithoutTenant(tableName) > 0;
        } catch (Exception e) {
            log.warn("Error checking table existence for {}: {}", tableName, e.getMessage());
            return false;
        }
    }

    private List<Map<String, Object>> fetchPlannedOrders(Long tenantId) {
        String sql = "SELECT * FROM " + TABLE_PLANNED_ORDER
                + " WHERE tenant_id = #{params.tenantId}"
                + " AND pe_plo_status IN ('planned', 'firmed')"
                + " AND pe_plo_order_type = 'production'"
                + " ORDER BY pe_plo_need_date ASC NULLS LAST, created_at ASC";
        Map<String, Object> params = Map.of("tenantId", tenantId);
        try {
            return dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
        } catch (Exception e) {
            log.warn("Error fetching planned orders: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> fetchResources(Long tenantId) {
        String sql = "SELECT * FROM " + TABLE_RESOURCE
                + " WHERE tenant_id = #{params.tenantId}"
                + " AND pe_res_status = 'active'"
                + " ORDER BY pe_res_code ASC";
        Map<String, Object> params = Map.of("tenantId", tenantId);
        try {
            return dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
        } catch (Exception e) {
            log.warn("Error fetching resources: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, List<Map<String, Object>>> fetchCalendars(Long tenantId, LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT * FROM " + TABLE_RESOURCE_CALENDAR
                + " WHERE tenant_id = #{params.tenantId}"
                + " AND pe_rc_date >= #{params.startDate}::date"
                + " AND pe_rc_date <= #{params.endDate}::date"
                + " AND (pe_rc_is_holiday IS NULL OR pe_rc_is_holiday = false)"
                + " ORDER BY pe_rc_resource_id, pe_rc_date, pe_rc_start_time";
        Map<String, Object> params = Map.of(
                "tenantId", tenantId,
                "startDate", startDate.toString(),
                "endDate", endDate.toString()
        );
        try {
            List<Map<String, Object>> calendars = dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
            return calendars.stream().collect(Collectors.groupingBy(c -> getString(c, "pe_rc_resource_id")));
        } catch (Exception e) {
            log.warn("Error fetching resource calendars: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private int calculateProcessingTime(BigDecimal orderQty, BigDecimal capacityPerHour) {
        if (capacityPerHour.compareTo(BigDecimal.ZERO) <= 0) {
            capacityPerHour = DEFAULT_CAPACITY_PER_HOUR;
        }
        // processing time in minutes = (orderQty / capacityPerHour) * 60
        BigDecimal hours = orderQty.divide(capacityPerHour, 4, RoundingMode.CEILING);
        return hours.multiply(new BigDecimal("60")).intValue();
    }

    private void writeScheduleResult(Long tenantId, String workOrderId, String operationName,
                                     String resourcePid, String resourceName,
                                     LocalDateTime startTime, LocalDateTime endTime,
                                     int setupTimeMin, int processingTimeMin,
                                     int scheduleVersion) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());
        data.put("pe_sched_work_order_id", workOrderId);
        data.put("pe_sched_operation_name", operationName != null ? operationName : "");
        data.put("pe_sched_resource_id", resourcePid);
        data.put("pe_sched_resource_name", resourceName != null ? resourceName : "");
        data.put("pe_sched_start_time", startTime);
        data.put("pe_sched_end_time", endTime);
        data.put("pe_sched_setup_time_min", setupTimeMin);
        data.put("pe_sched_processing_time_min", processingTimeMin);
        data.put("pe_sched_strategy", "forward");
        data.put("pe_sched_version", scheduleVersion);
        data.put("pe_sched_status", "scheduled");
        data.put("pe_sched_run_date", LocalDate.now());

        dynamicDataMapper.insert(TABLE_SCHEDULE_RESULT, data);
    }

    private int generateScheduleVersion() {
        // Simple version: YYYYMMDD * 100 + sequence within day
        LocalDate today = LocalDate.now();
        return today.getYear() * 1000000 + today.getMonthValue() * 10000
                + today.getDayOfMonth() * 100 + (int) (System.currentTimeMillis() % 100);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private static BigDecimal getDecimal(Map<String, Object> map, String key, BigDecimal defaultVal) {
        Object val = map.get(key);
        if (val instanceof BigDecimal) return (BigDecimal) val;
        if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
        if (val instanceof String && !((String) val).isEmpty()) {
            try {
                return new BigDecimal((String) val);
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        }
        return defaultVal;
    }

    // ==================== Resource availability tracker ====================

    /**
     * Tracks available time slots for a single resource across the scheduling horizon.
     * Uses calendar data when available, falls back to default 8h/day (08:00-16:00).
     */
    private static class ResourceTracker {
        final String resourcePid;
        final String resourceName;
        final BigDecimal capacityPerHour;
        // Available slots sorted by start time. Each slot: [start, end]
        final List<LocalDateTime[]> availableSlots;

        ResourceTracker(String resourcePid, String resourceName, BigDecimal capacityPerHour,
                        List<Map<String, Object>> calendarEntries, LocalDate startDate, LocalDate endDate) {
            this.resourcePid = resourcePid;
            this.resourceName = resourceName;
            this.capacityPerHour = capacityPerHour;
            this.availableSlots = new ArrayList<>();

            if (calendarEntries != null && !calendarEntries.isEmpty()) {
                // Build slots from calendar entries
                for (Map<String, Object> entry : calendarEntries) {
                    Object dateObj = entry.get("pe_rc_date");
                    LocalDate date = parseDate(dateObj);
                    if (date == null) continue;

                    LocalTime slotStart = parseTime(getString(entry, "pe_rc_start_time"), LocalTime.of(8, 0));
                    LocalTime slotEnd = parseTime(getString(entry, "pe_rc_end_time"), LocalTime.of(16, 0));

                    if (slotEnd.isAfter(slotStart)) {
                        availableSlots.add(new LocalDateTime[]{
                                LocalDateTime.of(date, slotStart),
                                LocalDateTime.of(date, slotEnd)
                        });
                    }
                }
            }

            if (availableSlots.isEmpty()) {
                // Fallback: generate default 8h slots for each working day (Mon-Fri)
                LocalDate d = startDate;
                while (!d.isAfter(endDate)) {
                    int dow = d.getDayOfWeek().getValue();
                    if (dow <= 5) { // Mon-Fri
                        availableSlots.add(new LocalDateTime[]{
                                LocalDateTime.of(d, LocalTime.of(8, 0)),
                                LocalDateTime.of(d, LocalTime.of(16, 0))
                        });
                    }
                    d = d.plusDays(1);
                }
            }

            // Sort by start time
            availableSlots.sort(Comparator.comparing(s -> s[0]));
        }

        /**
         * Find the earliest slot that can fit the required duration (in minutes).
         * Returns [start, end] or null if no slot available.
         */
        LocalDateTime[] findEarliestSlot(int durationMinutes) {
            for (LocalDateTime[] slot : availableSlots) {
                long availableMinutes = java.time.Duration.between(slot[0], slot[1]).toMinutes();
                if (availableMinutes >= durationMinutes) {
                    LocalDateTime start = slot[0];
                    LocalDateTime end = start.plusMinutes(durationMinutes);
                    return new LocalDateTime[]{start, end};
                }
            }
            // Try spanning multiple slots (simplified: just check if total remaining capacity suffices)
            // For MVP, if single slot doesn't fit, split across consecutive slots
            int remainingMinutes = durationMinutes;
            LocalDateTime spanStart = null;
            LocalDateTime spanEnd = null;
            List<Integer> usedSlotIndices = new ArrayList<>();

            for (int i = 0; i < availableSlots.size(); i++) {
                LocalDateTime[] slot = availableSlots.get(i);
                long slotMinutes = java.time.Duration.between(slot[0], slot[1]).toMinutes();
                if (slotMinutes <= 0) continue;

                if (spanStart == null) {
                    spanStart = slot[0];
                }
                usedSlotIndices.add(i);

                if (slotMinutes >= remainingMinutes) {
                    spanEnd = slot[0].plusMinutes(remainingMinutes);
                    return new LocalDateTime[]{spanStart, spanEnd};
                } else {
                    remainingMinutes -= (int) slotMinutes;
                    spanEnd = slot[1];
                }
            }
            return null; // Cannot fit within horizon
        }

        /**
         * Reserve a time slot (shrink or remove the used portion from available slots).
         */
        void reserveSlot(LocalDateTime start, LocalDateTime end) {
            List<LocalDateTime[]> newSlots = new ArrayList<>();
            boolean consumed = false;

            for (LocalDateTime[] slot : availableSlots) {
                if (consumed || end.isBefore(slot[0]) || end.equals(slot[0]) || start.isAfter(slot[1]) || start.equals(slot[1])) {
                    // No overlap
                    newSlots.add(slot);
                    continue;
                }

                // Overlap — split
                if (start.isAfter(slot[0])) {
                    // Keep portion before start
                    newSlots.add(new LocalDateTime[]{slot[0], start});
                }
                if (end.isBefore(slot[1])) {
                    // Keep portion after end
                    newSlots.add(new LocalDateTime[]{end, slot[1]});
                    consumed = true;
                } else {
                    // end >= slot[1], continue consuming next slot if needed
                    // For simplicity in MVP, mark as consumed
                    consumed = true;
                }
            }
            availableSlots.clear();
            availableSlots.addAll(newSlots);
        }

        private static LocalDate parseDate(Object obj) {
            if (obj instanceof LocalDate) return (LocalDate) obj;
            if (obj instanceof java.sql.Date) return ((java.sql.Date) obj).toLocalDate();
            if (obj instanceof String && !((String) obj).isEmpty()) {
                try {
                    return LocalDate.parse((String) obj);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private static LocalTime parseTime(String timeStr, LocalTime defaultTime) {
            if (timeStr == null || timeStr.isEmpty()) return defaultTime;
            try {
                return LocalTime.parse(timeStr);
            } catch (Exception e) {
                return defaultTime;
            }
        }
    }
}
