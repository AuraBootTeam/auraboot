package com.auraboot.module.oee.adapter;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicTableOeeAdapterTest {

    private static final long TENANT_ID = 990_002L;
    private static final String EQUIPMENT_PID = "MFG_EQ_PID_0001";

    private final DynamicDataMapper db = mock(DynamicDataMapper.class);
    private final DynamicTableOeeAdapter adapter = new DynamicTableOeeAdapter(db);

    @Test
    void listEquipment_supportsCurrentMfgPcbaEquipmentSchema() {
        currentMfgTablesExist();
        when(db.selectByQueryWithoutTenant(argThat(sql -> sql != null
                        && sql.contains("mt_mfg_equipment_pcba_asset")
                        && sql.contains("mfg_eq_code")
                        && sql.contains("mfg_eq_name")), anyMap()))
                .thenReturn(List.of(Map.of(
                    "pid", EQUIPMENT_PID,
                    "code", "EQ-SMT-01",
                    "name", "SMT Line 01")));

        List<OeeEquipmentRef> refs = adapter.listEquipment(TENANT_ID);

        assertEquals(1, refs.size());
        assertEquals(EQUIPMENT_PID, refs.get(0).getEquipmentId());
        assertEquals("EQ-SMT-01", refs.get(0).getCode());
        assertEquals("SMT Line 01", refs.get(0).getName());
    }

    @Test
    void fetch_supportsCurrentMfgPcbaOeeInputSchema() {
        currentMfgTablesExist();
        when(db.selectByQueryWithoutTenant(argThat(sql -> sql != null
                        && sql.contains("mt_mfg_work_order_operation_pcba_execution")
                        && sql.contains("mfg_wop_actual_qty")
                        && sql.contains("mfg_wop_defect_qty")
                        && sql.contains("mfg_res_capacity_per_hour")), anyMap()))
                .thenReturn(List.of(Map.of(
                    "act", new BigDecimal("600"),
                    "def", new BigDecimal("30"),
                    "cap", new BigDecimal("100"))));
        when(db.selectByQueryWithoutTenant(argThat(sql -> sql != null
                        && sql.contains("mt_mfg_resource_calendar_pcba_capacity")
                        && sql.contains("mfg_rc_available_hours")), anyMap()))
                .thenReturn(List.of(Map.of("h", new BigDecimal("8"))));
        when(db.selectByQueryWithoutTenant(argThat(sql -> sql != null
                        && sql.contains("mt_mfg_equipment_downtime_pcba_asset")
                        && sql.contains("mfg_dt_duration_hours")), anyMap()))
                .thenReturn(List.of(
                    Map.of("type", "planned", "hours", new BigDecimal("1")),
                    Map.of("type", "breakdown", "hours", new BigDecimal("1"))));

        OeeInputs inputs = adapter.fetch(OeeRequest.builder()
                .tenantId(TENANT_ID)
                .equipmentId(EQUIPMENT_PID)
                .windowStart(LocalDateTime.parse("2026-06-01T00:00:00"))
                .windowEnd(LocalDateTime.parse("2026-06-02T00:00:00"))
                .build());

        assertEquals(0, new BigDecimal("8").compareTo(inputs.getCalendarHours()));
        assertEquals(0, new BigDecimal("600").compareTo(inputs.getActualQty()));
        assertEquals(0, new BigDecimal("30").compareTo(inputs.getDefectQty()));
        assertEquals(0, new BigDecimal("100").compareTo(inputs.getCapacityPerHour()));
        assertEquals(2, inputs.getDowntimes().size());
    }

    private void currentMfgTablesExist() {
        when(db.checkTableExistsWithoutTenant(argThat(table -> table.startsWith("mt_pe_")))).thenReturn(0);
        when(db.checkTableExistsWithoutTenant("mt_mfg_equipment_pcba_asset")).thenReturn(1);
        when(db.checkTableExistsWithoutTenant("mt_mfg_equipment_downtime_pcba_asset")).thenReturn(1);
    }
}
