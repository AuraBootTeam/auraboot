package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicSchemaAccessRequest;
import com.auraboot.framework.meta.dto.DynamicSchemaAccessResult;
import com.auraboot.framework.meta.dto.FieldFilterRequest;
import com.auraboot.framework.meta.dto.FieldFilterResult;
import com.auraboot.framework.meta.service.SchemaAccessProjector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real-stack coverage IT for {@link SchemaAccessProjectorImpl} request-based paths:
 * calculateDynamicSchemaAccesss across the time/data/business context branches, and filterFields
 * over a field list (read/write permission resolution + masking). No PageSchema fixture needed.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SchemaAccessProjectorImpl Coverage IT — dynamic access + field filter")
class SchemaAccessProjectorImplCoverageIT {

    private static final long TENANT_ID = 992_000_001L;
    private static final long USER_ID = 992_000_002L;

    @Autowired
    private SchemaAccessProjector schemaAccessProjector;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "sap-test-pid", "sap-test-user");
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("calculateDynamicSchemaAccesss covers the time/data/business context branches")
    void dynamicAccessContextBranches() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("timeContext", Map.of("now", "2026-06-19T00:00:00Z"));
        ctx.put("dataContext", Map.of("region", "CN"));
        ctx.put("businessRules", List.of("rule1"));

        DynamicSchemaAccessRequest req = new DynamicSchemaAccessRequest();
        req.setUserId(USER_ID);
        req.setTenantId(TENANT_ID);
        req.setSchemaPid("sap_schema_pid");
        req.setContext(ctx);

        DynamicSchemaAccessResult result = schemaAccessProjector.calculateDynamicSchemaAccesss(req);
        assertNotNull(result);

        // also the empty-context path
        DynamicSchemaAccessRequest empty = new DynamicSchemaAccessRequest();
        empty.setUserId(USER_ID);
        empty.setTenantId(TENANT_ID);
        empty.setContext(new HashMap<>());
        assertNotNull(schemaAccessProjector.calculateDynamicSchemaAccesss(empty));
    }

    @Test
    @DisplayName("filterFields resolves read/write/masking over a field list")
    void filterFieldsOverList() {
        FieldFilterRequest req = FieldFilterRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode("sap_cov_model")
                .fields(List.of("name", "amount", "status"))
                .includeMaskingRules(true)
                .context(new HashMap<>())
                .build();

        FieldFilterResult result = schemaAccessProjector.filterFields(req);
        assertNotNull(result);
        assertEquals(3, result.getTotalCount());
    }
}
