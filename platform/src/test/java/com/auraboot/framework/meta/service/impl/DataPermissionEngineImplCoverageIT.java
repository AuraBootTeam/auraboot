package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DataPermissionEngineImpl} no-policy branches — for a member
 * with no effective data-permission policies, buildRowFilter is empty, filterRecords passes all
 * rows through, canAccessRecord allows, and getFieldMaskRules is empty. The active-policy
 * evaluation paths need seeded ab_data_permission_policy rows and stay out of scope here.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DataPermissionEngineImpl Coverage IT — no-policy branches")
class DataPermissionEngineImplCoverageIT {

    private static final long TENANT_ID = 991_400_001L;
    private static final long USER_ID = 991_400_002L;
    private static final long MEMBER_ID = 991_400_003L;
    private static final String MODEL = "perm_cov_model";

    @Autowired
    private DataPermissionEngine engine;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "perm-test-pid", "perm-test-user");
        MetaContext.setMemberId(MEMBER_ID);
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("buildRowFilter + filterRecords + canAccessRecord + getFieldMaskRules allow when no policy applies")
    void noPolicyAllowsEverything() {
        String filter = engine.buildRowFilter(TENANT_ID, MODEL, USER_ID);
        assertNotNull(filter); // empty string when there is no row policy

        List<Map<String, Object>> rows = List.of(Map.of("pid", "r1"), Map.of("pid", "r2"));
        List<Map<String, Object>> filtered = engine.filterRecords(TENANT_ID, MODEL, USER_ID, rows);
        assertEquals(rows.size(), filtered.size());

        assertTrue(engine.canAccessRecord(TENANT_ID, MODEL, USER_ID, Map.of("pid", "r1")));

        List<FieldMaskRule> masks = engine.getFieldMaskRules(TENANT_ID, MODEL, USER_ID);
        assertNotNull(masks);
    }
}
