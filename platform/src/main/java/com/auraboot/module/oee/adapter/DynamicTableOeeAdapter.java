package com.auraboot.module.oee.adapter;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Primary {@link OeeDataQueryPort} implementation that reads the PCBA manufacturing
 * dynamic tables ({@code mt_pe_*}) to assemble the raw OEE inputs for one equipment
 * over a time window.
 *
 * <p>Pattern mirrors {@code ApsSchedulingService}: {@link DynamicDataMapper#selectByQueryWithoutTenant}
 * with tenant isolation written into the SQL ({@code WHERE tenant_id = #{params.tenantId}}) and a
 * {@code checkTableExistsWithoutTenant} guard so a missing plugin yields zero-valued inputs instead
 * of an exception.</p>
 *
 * <h3>Grounded column / relationship facts (verified 2026-06-04 against the real plugin + platform)</h3>
 * <ul>
 *   <li><b>Identity column is {@code pid}</b> (a ULID string), not the numeric {@code id}. Reference
 *       fields store the target row's {@code pid} — proven by {@code DynamicDataServiceImpl} resolving
 *       reference display via {@code WHERE pid IN (...)}, and by {@code ApsSchedulingService} matching
 *       {@code pe_rc_resource_id} against resource {@code pid}. All joins below use {@code pid}.</li>
 *   <li><b>Downtime → equipment</b>: {@code pe_eq_downtime.pe_dt_equipment_id} is a reference to
 *       {@code pe_equipment} and stores the equipment {@code pid}. Fields used:
 *       {@code pe_dt_type} (dict {@code pe_downtime_type}: planned/unplanned/breakdown),
 *       {@code pe_dt_duration_hours}, {@code pe_dt_start_time}.</li>
 *   <li><b>Equipment → resource</b>: {@code pe_equipment.pe_eq_resource_id} references
 *       {@code pe_resource} (resource {@code pid}). Planned/calendar time and capacity are resource-level.</li>
 *   <li><b>Resource → calendar</b>: {@code pe_resource_calendar.pe_rc_resource_id} = resource {@code pid};
 *       {@code pe_rc_available_hours} summed; {@code pe_rc_date} is a DATE (compared with {@code ::date}).</li>
 *   <li><b>Work order operations</b>: {@code pe_work_order_op} has <b>no equipment column</b>; it links to a
 *       resource via {@code pe_woo_resource_id}. Output is therefore aggregated for the operations on the
 *       equipment's resource: {@code pe_woo_actual_qty}, {@code pe_woo_defect_qty}, filtered by
 *       {@code pe_woo_actual_start} in the window.</li>
 *   <li><b>Capacity</b>: {@code pe_resource.pe_res_capacity_per_hour} of the equipment's resource.</li>
 * </ul>
 *
 * <p><b>Residual scope note (resolved by the happy-path real-stack test, plan Task 8):</b> output is
 * attributed via the equipment's resource because no direct equipment→work-order link exists in the
 * model. If a deployment seeds multiple equipments onto one resource, output is shared at the resource
 * granularity — acceptable for the current single-equipment-per-resource PCBA layout, to be confirmed
 * against real seed data.</p>
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DynamicTableOeeAdapter implements OeeDataQueryPort {

    private final DynamicDataMapper db;

    private static final String TABLE_DOWNTIME = "mt_pe_eq_downtime";
    private static final String TABLE_EQUIPMENT = "mt_pe_equipment";
    private static final String TABLE_RESOURCE_CALENDAR = "mt_pe_resource_calendar";
    private static final String TABLE_WORK_ORDER_OP = "mt_pe_work_order_op";

    @Override
    public OeeInputs fetch(OeeRequest req) {
        // Plugin not imported -> zero inputs (the engine treats no-data as 0, never an error).
        if (!exists(TABLE_DOWNTIME) || !exists(TABLE_EQUIPMENT)) {
            log.info("OEE source tables not found (PCBA manufacturing plugin not imported). Returning zero inputs.");
            return zero();
        }

        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", req.getTenantId());
        p.put("eq", req.getEquipmentId());
        p.put("start", req.getWindowStart());
        p.put("end", req.getWindowEnd());

        Output output = fetchOutput(p);
        return OeeInputs.builder()
            .calendarHours(fetchCalendarHours(p))
            .downtimes(fetchDowntimes(p))
            .actualQty(output.actualQty)
            .defectQty(output.defectQty)
            .capacityPerHour(output.capacityPerHour)
            .build();
    }

    @Override
    public List<OeeEquipmentRef> listEquipment(Long tenantId) {
        // Plugin not imported -> no equipment (fleet roll-up degrades to empty, never an error).
        if (!exists(TABLE_EQUIPMENT)) {
            log.info("OEE equipment table not found (PCBA manufacturing plugin not imported). Returning no equipment.");
            return List.of();
        }
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        String sql = "SELECT pid, pe_eq_code AS code, pe_eq_name AS name "
            + "FROM " + TABLE_EQUIPMENT + " "
            + "WHERE tenant_id = #{params.tenantId} "
            + "AND (deleted_flag = false OR deleted_flag IS NULL) "
            + "ORDER BY pe_eq_code";
        List<OeeEquipmentRef> refs = new ArrayList<>();
        for (Map<String, Object> row : query(sql, p)) {
            refs.add(OeeEquipmentRef.builder()
                .equipmentId(str(row.get("pid")))
                .code(str(row.get("code")))
                .name(str(row.get("name")))
                .build());
        }
        return refs;
    }

    /** Downtime hours grouped by type for the equipment within the window. */
    private List<OeeInputs.Downtime> fetchDowntimes(Map<String, Object> p) {
        String sql = "SELECT pe_dt_type AS type, COALESCE(SUM(pe_dt_duration_hours), 0) AS hours "
            + "FROM " + TABLE_DOWNTIME + " "
            + "WHERE tenant_id = #{params.tenantId} "
            + "AND pe_dt_equipment_id = #{params.eq} "
            + "AND pe_dt_start_time >= #{params.start} "
            + "AND pe_dt_start_time < #{params.end} "
            + "GROUP BY pe_dt_type";
        List<OeeInputs.Downtime> downtimes = new ArrayList<>();
        for (Map<String, Object> row : query(sql, p)) {
            downtimes.add(OeeInputs.Downtime.builder()
                .type(str(row.get("type")))
                .hours(bd(row.get("hours")))
                .build());
        }
        return downtimes;
    }

    /**
     * Calendar (planned) hours = sum of the equipment's resource's available calendar hours in the window.
     * equipment.pid = #{eq} -> equipment.pe_eq_resource_id (resource pid) -> resource_calendar.pe_rc_resource_id.
     */
    private BigDecimal fetchCalendarHours(Map<String, Object> p) {
        String sql = "SELECT COALESCE(SUM(c.pe_rc_available_hours), 0) AS h "
            + "FROM " + TABLE_RESOURCE_CALENDAR + " c "
            + "JOIN " + TABLE_EQUIPMENT + " e ON e.pe_eq_resource_id = c.pe_rc_resource_id "
            + "WHERE e.tenant_id = #{params.tenantId} "
            + "AND e.pid = #{params.eq} "
            + "AND c.pe_rc_date >= #{params.start}::date "
            + "AND c.pe_rc_date < #{params.end}::date";
        return firstBd(query(sql, p), "h");
    }

    /**
     * Output + capacity for the equipment's resource within the window.
     * equipment.pid = #{eq} -> resource (pe_eq_resource_id) -> ops where pe_woo_resource_id = that resource pid.
     */
    private Output fetchOutput(Map<String, Object> p) {
        String sql = "SELECT COALESCE(SUM(wo.pe_woo_actual_qty), 0) AS act, "
            + "COALESCE(SUM(wo.pe_woo_defect_qty), 0) AS def, "
            + "COALESCE(MAX(r.pe_res_capacity_per_hour), 0) AS cap "
            + "FROM " + TABLE_EQUIPMENT + " e "
            + "JOIN mt_pe_resource r ON r.pid = e.pe_eq_resource_id AND r.tenant_id = #{params.tenantId} "
            + "LEFT JOIN " + TABLE_WORK_ORDER_OP + " wo ON wo.pe_woo_resource_id = e.pe_eq_resource_id "
            + "AND wo.tenant_id = #{params.tenantId} "
            + "AND wo.pe_woo_actual_start >= #{params.start} "
            + "AND wo.pe_woo_actual_start < #{params.end} "
            + "WHERE e.tenant_id = #{params.tenantId} "
            + "AND e.pid = #{params.eq}";
        List<Map<String, Object>> rows = query(sql, p);
        if (rows.isEmpty()) {
            return new Output(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Map<String, Object> row = rows.get(0);
        return new Output(bd(row.get("act")), bd(row.get("def")), bd(row.get("cap")));
    }

    // ==================== helpers ====================

    private List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        try {
            return db.selectByQueryWithoutTenant(sql, params);
        } catch (Exception e) {
            // Defensive: a schema mismatch should not crash the OEE endpoint; log and degrade to no data.
            // Real column/relationship correctness is asserted by the happy-path real-stack test (plan Task 8).
            log.warn("OEE query failed, degrading to empty result: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean exists(String table) {
        try {
            return db.checkTableExistsWithoutTenant(table) > 0;
        } catch (Exception e) {
            log.warn("Error checking table existence for {}: {}", table, e.getMessage());
            return false;
        }
    }

    private OeeInputs zero() {
        return OeeInputs.builder()
            .calendarHours(BigDecimal.ZERO)
            .downtimes(List.of())
            .actualQty(BigDecimal.ZERO)
            .defectQty(BigDecimal.ZERO)
            .capacityPerHour(BigDecimal.ZERO)
            .build();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static BigDecimal bd(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal firstBd(List<Map<String, Object>> rows, String key) {
        return rows.isEmpty() ? BigDecimal.ZERO : bd(rows.get(0).get(key));
    }

    /** Small value holder for the single output query (actual / defect / capacity). */
    private record Output(BigDecimal actualQty, BigDecimal defectQty, BigDecimal capacityPerHour) {
    }
}
