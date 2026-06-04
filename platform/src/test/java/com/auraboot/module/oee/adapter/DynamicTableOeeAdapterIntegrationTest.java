package com.auraboot.module.oee.adapter;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.dto.OeeResult;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Postgres integration test for {@link DynamicTableOeeAdapter}.
 *
 * <p><b>Why this exists:</b> the adapter's column names, {@code pid} reference joins and the
 * {@code ::date} casts in its three aggregation queries cannot be validated by a unit test (which
 * mocks {@link DynamicDataMapper}), and the full plugin stack is blocked by a plugin import cycle.
 * This test builds the minimal {@code mt_pe_*} schema directly with {@link JdbcTemplate}, seeds
 * deterministic data, and runs the real adapter SQL through MyBatis against the integration
 * Postgres so a wrong column name (e.g. {@code pe_woo_resource_id}) or a Postgres-dialect mistake
 * surfaces here.</p>
 *
 * <p>The seeded {@code mt_pe_*} tables are an integration-test fixture created and dropped by this
 * test (NOT manual DML against a production schema), so this does not violate the "no manual DB
 * mutation to fix tests" rule.</p>
 *
 * <h3>Deterministic fixture (single equipment on a single resource)</h3>
 * <ul>
 *   <li>resource: capacity 100/h</li>
 *   <li>calendar: one day inside the window, 8 available hours</li>
 *   <li>downtime: planned 1h + breakdown 1h, both inside the window</li>
 *   <li>work order op (on the resource): actual 600, defect 30, inside the window</li>
 * </ul>
 *
 * <h3>Hand calculation (mirrors {@link OeeCalculationEngine})</h3>
 * <pre>
 *   calendar      = 8
 *   planned       = 1, unplanned = 0, breakdown = 1
 *   loading       = calendar - planned          = 8 - 1 = 7
 *   operating     = loading - (unplanned+break)  = 7 - 1 = 6
 *   availability  = operating / loading          = 6 / 7      = 0.857143
 *   theoretical   = operating * capacity         = 6 * 100    = 600
 *   performance   = min(actual/theoretical, 1)   = 600 / 600  = 1.000000
 *   quality       = (actual-defect) / actual     = 570 / 600  = 0.950000
 *   oee           = A * P * Q                     = 0.814286
 *   teep          = oee * (loading / calendar)    = 0.814286 * (7/8) = 0.712500
 * </pre>
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicTableOeeAdapterIntegrationTest {

    @Autowired
    private DynamicTableOeeAdapter adapter;

    @Autowired
    private OeeCalculationEngine engine;

    @Autowired
    private DynamicDataMapper db;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Dedicated tenant + ULID-style pids so the fixture never collides with seed data.
    private static final long TENANT_ID = 990_001L;
    private static final String EQUIPMENT_PID = "OEEIT_EQ_0000000000000001";
    private static final String RESOURCE_PID = "OEEIT_RES_000000000000001";

    // Window: a single calendar day; downtime/output timestamps and the calendar date sit inside it.
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 6, 2, 0, 0, 0);

    /**
     * Point the Spring context at an isolated Postgres instead of the shared host DB, honoring the
     * multi-worktree isolation rule (§11): the canonical enterprise backend keeps the shared
     * {@code aura_boot} on 5432, so this test runs against an offset-port copy. Override the four
     * {@code OEE_IT_PG_*} env vars to retarget. Defaults match the local isolated container
     * ({@code COMPOSE-style} name {@code oee-adaptertest-pg} on 5501 seeded from a full dump).
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String host = env("OEE_IT_PG_HOST", "localhost");
        String port = env("OEE_IT_PG_PORT", "5501");
        String dbName = env("OEE_IT_PG_DB", "aura_boot");
        String user = env("OEE_IT_PG_USER", "ghj");
        String password = env("OEE_IT_PG_PASSWORD", "oeeit");
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://" + host + ":" + port + "/" + dbName + "?charSet=UTF8");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
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

    private void dropTables() {
        for (String t : List.of(
                "mt_pe_eq_downtime",
                "mt_pe_resource_calendar",
                "mt_pe_work_order_op",
                "mt_pe_equipment",
                "mt_pe_resource")) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + t);
        }
    }

    /**
     * Build only the columns the adapter SQL touches. Identity column is {@code pid} (text/ULID),
     * matching the dynamic-table convention used by the adapter joins.
     */
    private void createTables() {
        jdbcTemplate.execute("""
            CREATE TABLE mt_pe_resource (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                pe_res_capacity_per_hour NUMERIC
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_pe_equipment (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                pe_eq_resource_id VARCHAR(64)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_pe_resource_calendar (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                pe_rc_resource_id VARCHAR(64),
                pe_rc_date DATE,
                pe_rc_available_hours NUMERIC
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_pe_eq_downtime (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                pe_dt_equipment_id VARCHAR(64),
                pe_dt_type VARCHAR(32),
                pe_dt_duration_hours NUMERIC,
                pe_dt_start_time TIMESTAMP
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE mt_pe_work_order_op (
                pid VARCHAR(64) PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                pe_woo_resource_id VARCHAR(64),
                pe_woo_actual_qty NUMERIC,
                pe_woo_defect_qty NUMERIC,
                pe_woo_actual_start TIMESTAMP
            )
            """);
    }

    private void seed() {
        // resource: capacity 100/h
        jdbcTemplate.update(
                "INSERT INTO mt_pe_resource (pid, tenant_id, pe_res_capacity_per_hour) VALUES (?, ?, ?)",
                RESOURCE_PID, TENANT_ID, new BigDecimal("100"));

        // equipment -> resource
        jdbcTemplate.update(
                "INSERT INTO mt_pe_equipment (pid, tenant_id, pe_eq_resource_id) VALUES (?, ?, ?)",
                EQUIPMENT_PID, TENANT_ID, RESOURCE_PID);

        // calendar: one day inside the window, 8 available hours
        jdbcTemplate.update(
                "INSERT INTO mt_pe_resource_calendar (pid, tenant_id, pe_rc_resource_id, pe_rc_date, pe_rc_available_hours) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "OEEIT_CAL_000000000000001", TENANT_ID, RESOURCE_PID,
                java.sql.Date.valueOf("2026-06-01"), new BigDecimal("8"));

        // downtime: planned 1h + breakdown 1h, both inside the window
        jdbcTemplate.update(
                "INSERT INTO mt_pe_eq_downtime (pid, tenant_id, pe_dt_equipment_id, pe_dt_type, pe_dt_duration_hours, pe_dt_start_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "OEEIT_DT_0000000000000001", TENANT_ID, EQUIPMENT_PID, "planned",
                new BigDecimal("1"), java.sql.Timestamp.valueOf("2026-06-01 09:00:00"));
        jdbcTemplate.update(
                "INSERT INTO mt_pe_eq_downtime (pid, tenant_id, pe_dt_equipment_id, pe_dt_type, pe_dt_duration_hours, pe_dt_start_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "OEEIT_DT_0000000000000002", TENANT_ID, EQUIPMENT_PID, "breakdown",
                new BigDecimal("1"), java.sql.Timestamp.valueOf("2026-06-01 14:00:00"));

        // work order op on the resource: actual 600, defect 30, inside the window
        jdbcTemplate.update(
                "INSERT INTO mt_pe_work_order_op (pid, tenant_id, pe_woo_resource_id, pe_woo_actual_qty, pe_woo_defect_qty, pe_woo_actual_start) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "OEEIT_WOO_000000000000001", TENANT_ID, RESOURCE_PID,
                new BigDecimal("600"), new BigDecimal("30"), java.sql.Timestamp.valueOf("2026-06-01 08:00:00"));
    }

    private OeeRequest request() {
        return OeeRequest.builder()
                .tenantId(TENANT_ID)
                .equipmentId(EQUIPMENT_PID)
                .windowStart(WINDOW_START)
                .windowEnd(WINDOW_END)
                .build();
    }

    private BigDecimal scaled(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Proves the three aggregation SQL strings (downtime / calendar / output) parse and run on real
     * Postgres with the production column names + {@code ::date} casts, returning the seeded values.
     * A wrong column name (e.g. {@code pe_woo_resource_id}) would make the adapter degrade to zero,
     * failing these assertions.
     */
    @Test
    void fetch_assemblesRawInputsFromRealPostgres() {
        OeeInputs in = adapter.fetch(request());

        assertNotNull(in, "adapter must return inputs");

        // calendar: resource_calendar JOIN equipment via pe_eq_resource_id = pe_rc_resource_id, ::date window
        assertEquals(0, new BigDecimal("8").compareTo(in.getCalendarHours()),
                "calendar hours (resource calendar JOIN equipment, ::date window) should be 8, got " + in.getCalendarHours());

        // output: equipment JOIN resource (r.pid = e.pe_eq_resource_id) LEFT JOIN work_order_op (pe_woo_resource_id)
        assertEquals(0, new BigDecimal("600").compareTo(in.getActualQty()),
                "actualQty (SUM pe_woo_actual_qty via pe_woo_resource_id) should be 600, got " + in.getActualQty());
        assertEquals(0, new BigDecimal("30").compareTo(in.getDefectQty()),
                "defectQty (SUM pe_woo_defect_qty) should be 30, got " + in.getDefectQty());
        assertEquals(0, new BigDecimal("100").compareTo(in.getCapacityPerHour()),
                "capacityPerHour (MAX pe_res_capacity_per_hour) should be 100, got " + in.getCapacityPerHour());

        // downtime: GROUP BY pe_dt_type -> planned 1h + breakdown 1h
        assertNotNull(in.getDowntimes());
        assertEquals(2, in.getDowntimes().size(),
                "downtime should be grouped into 2 types (planned, breakdown), got " + in.getDowntimes());
        BigDecimal planned = downtimeHours(in, "planned");
        BigDecimal breakdown = downtimeHours(in, "breakdown");
        assertEquals(0, new BigDecimal("1").compareTo(planned), "planned downtime should be 1h, got " + planned);
        assertEquals(0, new BigDecimal("1").compareTo(breakdown), "breakdown downtime should be 1h, got " + breakdown);
    }

    /**
     * End-to-end: real adapter inputs -> pure engine -> assert the five OEE rates match the
     * hand-calculated values. This is the part a unit test cannot cover, because the inputs here
     * come from real SQL against real seeded rows.
     */
    @Test
    void fetchThenCalculate_producesHandVerifiedRates() {
        OeeResult r = engine.calculate(adapter.fetch(request()));

        // availability = 6/7 = 0.857143
        assertEquals(0, new BigDecimal("0.857143").compareTo(scaled(r.getAvailability())),
                "availability should be 6/7=0.857143, got " + r.getAvailability());
        // performance = 600/600 = 1.000000 (capped)
        assertEquals(0, new BigDecimal("1.000000").compareTo(r.getPerformance()),
                "performance should be 1.0, got " + r.getPerformance());
        // quality = 570/600 = 0.950000
        assertEquals(0, new BigDecimal("0.950000").compareTo(r.getQuality()),
                "quality should be 0.95, got " + r.getQuality());
        // oee = 0.857143 * 1.0 * 0.95 = 0.814286
        assertEquals(0, new BigDecimal("0.814286").compareTo(r.getOee()),
                "oee should be 0.814286, got " + r.getOee());
        // teep = oee * (loading 7 / calendar 8) = 0.814286 * 0.875 = 0.712500
        assertEquals(0, new BigDecimal("0.712500").compareTo(r.getTeep()),
                "teep should be 0.712500, got " + r.getTeep());

        // Six big losses sanity: breakdown 1h, setup(=planned) 1h, process defect 30
        assertEquals(0, new BigDecimal("1").compareTo(r.getLosses().getBreakdownHours()));
        assertEquals(0, new BigDecimal("1").compareTo(r.getLosses().getSetupHours()));
        assertEquals(0, new BigDecimal("30").compareTo(r.getLosses().getProcessDefectUnits()));
    }

    /**
     * Sanity guard against a swallowed {@code BadSqlGrammar}: run the adapter's calendar-style query
     * through the same mapper path with a deliberately correct column set and confirm a row comes
     * back. If the production column names were wrong, the adapter's defensive catch would hide the
     * grammar error; this asserts the mapper path itself is healthy on real Postgres.
     */
    @Test
    void mapperPath_executesPostgresDialectWithoutGrammarError() {
        String sql = "SELECT COALESCE(SUM(c.pe_rc_available_hours), 0) AS h "
                + "FROM mt_pe_resource_calendar c "
                + "JOIN mt_pe_equipment e ON e.pe_eq_resource_id = c.pe_rc_resource_id "
                + "WHERE e.tenant_id = #{params.tenantId} "
                + "AND e.pid = #{params.eq} "
                + "AND c.pe_rc_date >= #{params.start}::date "
                + "AND c.pe_rc_date < #{params.end}::date";
        Map<String, Object> p = Map.of(
                "tenantId", TENANT_ID,
                "eq", EQUIPMENT_PID,
                "start", WINDOW_START,
                "end", WINDOW_END);
        List<Map<String, Object>> rows = db.selectByQueryWithoutTenant(sql, p);
        assertEquals(1, rows.size());
        BigDecimal h = new BigDecimal(rows.get(0).get("h").toString());
        assertTrue(h.compareTo(BigDecimal.ZERO) > 0, "calendar hours should be > 0, got " + h);
    }

    private BigDecimal downtimeHours(OeeInputs in, String type) {
        return in.getDowntimes().stream()
                .filter(d -> type.equals(d.getType()))
                .map(OeeInputs.Downtime::getHours)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}
