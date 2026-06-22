package com.auraboot.module.oee.adapter;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed {@link OeeDataQueryPort} implementation that reads the PCBA manufacturing
 * dynamic tables to assemble the raw OEE inputs for one equipment over a time window.
 * Current PCBA plugins publish {@code mt_mfg_*} tables; legacy {@code mt_pe_*} tables are still
 * supported as a compatibility fallback. Wrapped by {@link TelemetryEnrichingOeeDataQueryPort} (the {@code @Primary}
 * port) which overlays telemetry-derived A/P/Q signals when an {@code OeeTelemetrySource} is present.
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
@RequiredArgsConstructor
public class DynamicTableOeeAdapter implements OeeDataQueryPort {

    private final DynamicDataMapper db;

    private static final PcbaSchema CURRENT_MFG = new PcbaSchema(
            "current-mfg",
            "mt_mfg_equipment_downtime_pcba_asset",
            "mt_mfg_equipment_pcba_asset",
            "mt_mfg_resource_calendar_pcba_capacity",
            "mt_mfg_work_order_operation_pcba_execution",
            "mt_mfg_resource_pcba_capacity",
            "mfg_dt_equipment_id",
            "mfg_dt_type",
            "mfg_dt_duration_hours",
            "mfg_dt_start_time",
            "mfg_eq_resource_id",
            "mfg_eq_code",
            "mfg_eq_name",
            "mfg_rc_resource_id",
            "mfg_rc_date",
            "mfg_rc_available_hours",
            "mfg_wop_resource_id",
            "mfg_wop_actual_qty",
            "mfg_wop_defect_qty",
            "mfg_wop_actual_start",
            "mfg_res_capacity_per_hour");

    private static final PcbaSchema LEGACY_PE = new PcbaSchema(
            "legacy-pe",
            "mt_pe_eq_downtime",
            "mt_pe_equipment",
            "mt_pe_resource_calendar",
            "mt_pe_work_order_op",
            "mt_pe_resource",
            "pe_dt_equipment_id",
            "pe_dt_type",
            "pe_dt_duration_hours",
            "pe_dt_start_time",
            "pe_eq_resource_id",
            "pe_eq_code",
            "pe_eq_name",
            "pe_rc_resource_id",
            "pe_rc_date",
            "pe_rc_available_hours",
            "pe_woo_resource_id",
            "pe_woo_actual_qty",
            "pe_woo_defect_qty",
            "pe_woo_actual_start",
            "pe_res_capacity_per_hour");

    @Override
    public OeeInputs fetch(OeeRequest req) {
        PcbaSchema schema = activeSchema();
        // Plugin not imported -> zero inputs (the engine treats no-data as 0, never an error).
        if (schema == null) {
            log.info("OEE source tables not found (PCBA manufacturing plugin not imported). Returning zero inputs.");
            return zero();
        }

        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", req.getTenantId());
        p.put("eq", req.getEquipmentId());
        p.put("start", req.getWindowStart());
        p.put("end", req.getWindowEnd());

        Output output = fetchOutput(schema, p);
        return OeeInputs.builder()
            .calendarHours(fetchCalendarHours(schema, p))
            .downtimes(fetchDowntimes(schema, p))
            .actualQty(output.actualQty)
            .defectQty(output.defectQty)
            .capacityPerHour(output.capacityPerHour)
            .build();
    }

    @Override
    public List<OeeEquipmentRef> listEquipment(Long tenantId) {
        PcbaSchema schema = equipmentSchema();
        // Plugin not imported -> no equipment (fleet roll-up degrades to empty, never an error).
        if (schema == null) {
            log.info("OEE equipment table not found (PCBA manufacturing plugin not imported). Returning no equipment.");
            return List.of();
        }
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        // No soft-delete filter: the pe_equipment dynamic table has no deleted_flag column (the
        // platform's mt_pe_* tables do not carry one), matching the other OEE adapter queries.
        String sql = "SELECT pid, " + schema.equipmentCodeColumn + " AS code, "
                + schema.equipmentNameColumn + " AS name "
                + "FROM " + schema.equipmentTable + " "
                + "WHERE tenant_id = #{params.tenantId} "
                + "ORDER BY " + schema.equipmentCodeColumn;
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
    private List<OeeInputs.Downtime> fetchDowntimes(PcbaSchema schema, Map<String, Object> p) {
        String sql = "SELECT " + schema.downtimeTypeColumn + " AS type, COALESCE(SUM("
                + schema.downtimeHoursColumn + "), 0) AS hours "
                + "FROM " + schema.downtimeTable + " "
                + "WHERE tenant_id = #{params.tenantId} "
                + "AND " + schema.downtimeEquipmentColumn + " = #{params.eq} "
                + "AND " + schema.downtimeStartColumn + " >= #{params.start} "
                + "AND " + schema.downtimeStartColumn + " < #{params.end} "
                + "GROUP BY " + schema.downtimeTypeColumn;
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
    private BigDecimal fetchCalendarHours(PcbaSchema schema, Map<String, Object> p) {
        String sql = "SELECT COALESCE(SUM(c." + schema.calendarAvailableHoursColumn + "), 0) AS h "
                + "FROM " + schema.resourceCalendarTable + " c "
                + "JOIN " + schema.equipmentTable + " e ON e." + schema.equipmentResourceColumn
                + " = c." + schema.calendarResourceColumn + " "
                + "WHERE e.tenant_id = #{params.tenantId} "
                + "AND e.pid = #{params.eq} "
                + "AND c." + schema.calendarDateColumn + " >= #{params.start}::date "
                + "AND c." + schema.calendarDateColumn + " < #{params.end}::date";
        return firstBd(query(sql, p), "h");
    }

    /**
     * Output + capacity for the equipment's resource within the window.
     * equipment.pid = #{eq} -> resource (pe_eq_resource_id) -> ops where pe_woo_resource_id = that resource pid.
     */
    private Output fetchOutput(PcbaSchema schema, Map<String, Object> p) {
        String sql = "SELECT COALESCE(SUM(wo." + schema.workOrderActualQtyColumn + "), 0) AS act, "
                + "COALESCE(SUM(wo." + schema.workOrderDefectQtyColumn + "), 0) AS def, "
                + "COALESCE(MAX(r." + schema.resourceCapacityColumn + "), 0) AS cap "
                + "FROM " + schema.equipmentTable + " e "
                + "JOIN " + schema.resourceTable + " r ON r.pid = e." + schema.equipmentResourceColumn
                + " AND r.tenant_id = #{params.tenantId} "
                + "LEFT JOIN " + schema.workOrderOperationTable + " wo ON wo." + schema.workOrderResourceColumn
                + " = e." + schema.equipmentResourceColumn + " "
                + "AND wo.tenant_id = #{params.tenantId} "
                + "AND wo." + schema.workOrderActualStartColumn + " >= #{params.start} "
                + "AND wo." + schema.workOrderActualStartColumn + " < #{params.end} "
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

    private PcbaSchema activeSchema() {
        if (exists(CURRENT_MFG.equipmentTable) && exists(CURRENT_MFG.downtimeTable)) {
            return CURRENT_MFG;
        }
        if (exists(LEGACY_PE.equipmentTable) && exists(LEGACY_PE.downtimeTable)) {
            return LEGACY_PE;
        }
        return null;
    }

    private PcbaSchema equipmentSchema() {
        if (exists(CURRENT_MFG.equipmentTable)) {
            return CURRENT_MFG;
        }
        if (exists(LEGACY_PE.equipmentTable)) {
            return LEGACY_PE;
        }
        return null;
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

    private record PcbaSchema(
            String label,
            String downtimeTable,
            String equipmentTable,
            String resourceCalendarTable,
            String workOrderOperationTable,
            String resourceTable,
            String downtimeEquipmentColumn,
            String downtimeTypeColumn,
            String downtimeHoursColumn,
            String downtimeStartColumn,
            String equipmentResourceColumn,
            String equipmentCodeColumn,
            String equipmentNameColumn,
            String calendarResourceColumn,
            String calendarDateColumn,
            String calendarAvailableHoursColumn,
            String workOrderResourceColumn,
            String workOrderActualQtyColumn,
            String workOrderDefectQtyColumn,
            String workOrderActualStartColumn,
            String resourceCapacityColumn) {
    }
}
