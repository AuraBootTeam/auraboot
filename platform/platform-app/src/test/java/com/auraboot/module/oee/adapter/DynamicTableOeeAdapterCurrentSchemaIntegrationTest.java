package com.auraboot.module.oee.adapter;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.dto.OeeResult;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-Postgres coverage for the current PCBA manufacturing model names
 * ({@code mt_mfg_*}). The legacy companion test keeps {@code mt_pe_*} compatibility covered.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicTableOeeAdapterCurrentSchemaIntegrationTest {

    @Autowired
    private DynamicTableOeeAdapter adapter;

    @Autowired
    private OeeCalculationEngine engine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long TENANT_ID = 990_003L;
    private static final String EQUIPMENT_PID = "OEEIT_MFG_EQ_000000000001";
    private static final String RESOURCE_PID = "OEEIT_MFG_RES_00000000001";
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 6, 2, 0, 0, 0);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String datasourceUrl = firstEnv("SPRING_DATASOURCE_URL", "DATABASE_URL");
        if (datasourceUrl == null && hasAnyEnv("OEE_IT_PG_HOST", "OEE_IT_PG_PORT", "OEE_IT_PG_DB")) {
            String host = env("OEE_IT_PG_HOST", "localhost");
            String port = env("OEE_IT_PG_PORT", "5432");
            String dbName = env("OEE_IT_PG_DB", "aura_boot");
            datasourceUrl = "jdbc:postgresql://" + host + ":" + port + "/" + dbName + "?charSet=UTF8";
        }
        if (datasourceUrl == null) {
            return;
        }
        String user = env("SPRING_DATASOURCE_USERNAME", env("OEE_IT_PG_USER", "ghj"));
        String password = env("SPRING_DATASOURCE_PASSWORD", env("OEE_IT_PG_PASSWORD", ""));
        String resolvedDatasourceUrl = datasourceUrl;
        registry.add("spring.datasource.url", () -> resolvedDatasourceUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
    }

    private static String firstEnv(String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasAnyEnv(String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    @BeforeAll
    void createSchemaAndSeed() {
        dropTables();
        createTables();
        seed();
    }

    @AfterAll
    void cleanup() {
        dropTables();
    }

    @Test
    void listEquipment_returnsCurrentMfgEquipment() {
        List<OeeEquipmentRef> equipment = adapter.listEquipment(TENANT_ID);

        assertEquals(1, equipment.size());
        assertEquals(EQUIPMENT_PID, equipment.get(0).getEquipmentId());
        assertEquals("EQ-MFG-IT-1", equipment.get(0).getCode());
        assertEquals("SMT Line Current IT", equipment.get(0).getName());
    }

    @Test
    void fetchThenCalculate_currentMfgSchemaProducesHandVerifiedRates() {
        OeeInputs inputs = adapter.fetch(request());
        assertEquals(0, new BigDecimal("8").compareTo(inputs.getCalendarHours()));
        assertEquals(0, new BigDecimal("600").compareTo(inputs.getActualQty()));
        assertEquals(0, new BigDecimal("30").compareTo(inputs.getDefectQty()));
        assertEquals(0, new BigDecimal("100").compareTo(inputs.getCapacityPerHour()));
        assertEquals(2, inputs.getDowntimes().size());

        OeeResult result = engine.calculate(inputs);
        assertEquals(0, new BigDecimal("0.857143").compareTo(scaled(result.getAvailability())));
        assertEquals(0, new BigDecimal("1.000000").compareTo(result.getPerformance()));
        assertEquals(0, new BigDecimal("0.950000").compareTo(result.getQuality()));
        assertEquals(0, new BigDecimal("0.814286").compareTo(result.getOee()));
        assertEquals(0, new BigDecimal("0.712500").compareTo(result.getTeep()));
    }

    private OeeRequest request() {
        return OeeRequest.builder()
                .tenantId(TENANT_ID)
                .equipmentId(EQUIPMENT_PID)
                .equipmentCode("EQ-MFG-IT-1")
                .windowStart(WINDOW_START)
                .windowEnd(WINDOW_END)
                .build();
    }

    private BigDecimal scaled(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private void dropTables() {
        for (String table : List.of(
                "mt_mfg_equipment_downtime_pcba_asset",
                "mt_mfg_resource_calendar_pcba_capacity",
                "mt_mfg_work_order_operation_pcba_execution",
                "mt_mfg_equipment_pcba_asset",
                "mt_mfg_resource_pcba_capacity")) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private void createTables() {
        jdbcTemplate.execute("""
            CREATE TABLE mt_mfg_resource_pcba_capacity (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                mfg_res_capacity_per_hour NUMERIC
            )
                """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_mfg_equipment_pcba_asset (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                mfg_eq_resource_id VARCHAR(64),
                mfg_eq_code VARCHAR(64),
                mfg_eq_name VARCHAR(128)
            )
                """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_mfg_resource_calendar_pcba_capacity (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                mfg_rc_resource_id VARCHAR(64),
                mfg_rc_date DATE,
                mfg_rc_available_hours NUMERIC
            )
                """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_mfg_equipment_downtime_pcba_asset (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                mfg_dt_equipment_id VARCHAR(64),
                mfg_dt_type VARCHAR(32),
                mfg_dt_duration_hours NUMERIC,
                mfg_dt_start_time TIMESTAMP
            )
                """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_mfg_work_order_operation_pcba_execution (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                mfg_wop_resource_id VARCHAR(64),
                mfg_wop_actual_qty NUMERIC,
                mfg_wop_defect_qty NUMERIC,
                mfg_wop_actual_start TIMESTAMP
            )
                """);
    }

    private void seed() {
        jdbcTemplate.update(
                "INSERT INTO mt_mfg_resource_pcba_capacity "
                        + "(pid, tenant_id, mfg_res_capacity_per_hour) VALUES (?, ?, ?)",
                RESOURCE_PID, TENANT_ID, new BigDecimal("100"));
        jdbcTemplate.update(
                "INSERT INTO mt_mfg_equipment_pcba_asset "
                        + "(pid, tenant_id, mfg_eq_resource_id, mfg_eq_code, mfg_eq_name) VALUES (?, ?, ?, ?, ?)",
                EQUIPMENT_PID, TENANT_ID, RESOURCE_PID, "EQ-MFG-IT-1", "SMT Line Current IT");
        jdbcTemplate.update(
                "INSERT INTO mt_mfg_resource_calendar_pcba_capacity "
                        + "(pid, tenant_id, mfg_rc_resource_id, mfg_rc_date, mfg_rc_available_hours) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "OEEIT_MFG_CAL_000000000001", TENANT_ID, RESOURCE_PID,
                java.sql.Date.valueOf("2026-06-01"), new BigDecimal("8"));
        jdbcTemplate.update(
                "INSERT INTO mt_mfg_equipment_downtime_pcba_asset "
                        + "(pid, tenant_id, mfg_dt_equipment_id, mfg_dt_type, "
                        + "mfg_dt_duration_hours, mfg_dt_start_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "OEEIT_MFG_DT_000000000001", TENANT_ID, EQUIPMENT_PID, "planned",
                new BigDecimal("1"), java.sql.Timestamp.valueOf("2026-06-01 09:00:00"));
        jdbcTemplate.update(
                "INSERT INTO mt_mfg_equipment_downtime_pcba_asset "
                        + "(pid, tenant_id, mfg_dt_equipment_id, mfg_dt_type, "
                        + "mfg_dt_duration_hours, mfg_dt_start_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "OEEIT_MFG_DT_000000000002", TENANT_ID, EQUIPMENT_PID, "breakdown",
                new BigDecimal("1"), java.sql.Timestamp.valueOf("2026-06-01 14:00:00"));
        jdbcTemplate.update(
                "INSERT INTO mt_mfg_work_order_operation_pcba_execution "
                        + "(pid, tenant_id, mfg_wop_resource_id, mfg_wop_actual_qty, "
                        + "mfg_wop_defect_qty, mfg_wop_actual_start) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "OEEIT_MFG_WOP_00000000001", TENANT_ID, RESOURCE_PID,
                new BigDecimal("600"), new BigDecimal("30"), java.sql.Timestamp.valueOf("2026-06-01 08:00:00"));
    }
}
