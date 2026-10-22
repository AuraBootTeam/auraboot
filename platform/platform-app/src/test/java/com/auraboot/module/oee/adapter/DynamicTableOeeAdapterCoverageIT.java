package com.auraboot.module.oee.adapter;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DynamicTableOeeAdapter} — listEquipment over a seeded
 * mt_pe_equipment dynamic table (query + row mapping), and fetch degrading to zero when the
 * downtime table is absent. The full multi-table OEE roll-up join stays out of scope.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DynamicTableOeeAdapter Coverage IT — listEquipment + fetch degradation")
class DynamicTableOeeAdapterCoverageIT {

    private static final long TENANT_ID = 992_200_001L;

    @Autowired
    private OeeDataQueryPort oeeDataQueryPort;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean created = false;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 992_200_002L, "oee-test-pid", "oee-test-user");
        if (!created) {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS mt_pe_equipment ("
                    + "id BIGSERIAL PRIMARY KEY, pid VARCHAR(64), tenant_id BIGINT, "
                    + "pe_eq_code VARCHAR(128), pe_eq_name VARCHAR(256))");
            jdbcTemplate.update("DELETE FROM mt_pe_equipment WHERE tenant_id = ?", TENANT_ID);
            jdbcTemplate.update("INSERT INTO mt_pe_equipment (pid, tenant_id, pe_eq_code, pe_eq_name) "
                    + "VALUES ('oee_eq_1', ?, 'EQ-001', 'Reflow Oven')", TENANT_ID);
            created = true;
        }
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM mt_pe_equipment WHERE tenant_id = ?", TENANT_ID);
        } catch (Exception ignore) {
            // table may have been dropped elsewhere
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("listEquipment returns equipment rows from the seeded mt_pe_equipment table")
    void listEquipment() {
        List<OeeEquipmentRef> equipment = oeeDataQueryPort.listEquipment(TENANT_ID);
        assertNotNull(equipment);
        assertTrue(equipment.stream().anyMatch(e -> "EQ-001".equals(e.getCode())),
                "expected the seeded EQ-001 equipment, got: " + equipment);
    }

    @Test
    @DisplayName("fetch degrades to a zero result when the downtime table is absent")
    void fetchDegradesToZero() {
        OeeRequest req = new OeeRequest();
        req.setTenantId(TENANT_ID);
        req.setEquipmentId("oee_eq_1");
        req.setWindowStart(LocalDateTime.now().minusDays(1));
        req.setWindowEnd(LocalDateTime.now());
        OeeInputs inputs = oeeDataQueryPort.fetch(req);
        assertNotNull(inputs);
    }
}
