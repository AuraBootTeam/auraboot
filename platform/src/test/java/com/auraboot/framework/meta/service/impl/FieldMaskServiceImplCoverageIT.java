package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.mapper.FieldMaskConfigMapper;
import com.auraboot.framework.meta.service.FieldMaskService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Real-stack coverage IT for {@link FieldMaskServiceImpl} config CRUD + list/detail masking.
 * Complements the pure {@code FieldMaskServiceImplTest} (maskValue helpers). Exercises the
 * {@code @CacheEvict("fieldMaskConfig")} path that 500'd in production until the cache region
 * was registered (caught alongside this IT, 2026-06-19; see findings doc wave 5/6).
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FieldMaskServiceImpl Coverage IT — config CRUD + list/detail masking")
class FieldMaskServiceImplCoverageIT {

    private static final long TENANT_ID = 990_300_001L;
    private final AtomicLong modelSeq = new AtomicLong();

    @Autowired
    private FieldMaskService fieldMaskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @SpyBean
    private FieldMaskConfigMapper fieldMaskConfigMapper;

    private String model;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_002L, "fm-test-pid", "fm-test-user");
        model = "fmcov_" + modelSeq.incrementAndGet() + "_" + Math.abs(System.nanoTime() % 100000);
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_field_mask_config WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private FieldMaskConfig config(String fieldCode, String maskType, boolean enabled) {
        FieldMaskConfig c = new FieldMaskConfig();
        c.setModelCode(model);
        c.setFieldCode(fieldCode);
        c.setMaskType(maskType);
        c.setReplacementChar("*");
        c.setEnabled(enabled);
        c.setApplyToList(true);
        c.setApplyToDetail(true);
        c.setApplyToExport(false);
        return c;
    }

    @Test
    @DisplayName("saveConfig (insert + update), listConfigs, getEnabledConfigs, deleteConfig")
    void crud() {
        FieldMaskConfig saved = fieldMaskService.saveConfig(config("phone", "phone", true));
        assertNotNull(saved.getId());

        List<FieldMaskConfig> configs = fieldMaskService.listConfigs(model);
        assertTrue(configs.stream().anyMatch(c -> c.getId().equals(saved.getId())));
        assertEquals(1, fieldMaskService.getEnabledConfigs(model).size());

        // re-save same model+field -> update path (id preserved)
        FieldMaskConfig update = config("phone", "id_card", true);
        FieldMaskConfig updated = fieldMaskService.saveConfig(update);
        assertEquals(saved.getId(), updated.getId());
        assertEquals("id_card", fieldMaskService.listConfigs(model).get(0).getMaskType());
        assertEquals("id_card", fieldMaskService.getEnabledConfigs(model).get(0).getMaskType());

        fieldMaskService.deleteConfig(saved.getId());
        assertTrue(fieldMaskService.listConfigs(model).isEmpty());
        assertTrue(fieldMaskService.getEnabledConfigs(model).isEmpty());
    }

    @Test
    @DisplayName("applyMaskingForList / ForDetail mask the configured field for a non-exempt user")
    void applyMasking() {
        fieldMaskService.saveConfig(config("phone", "phone", true));

        Map<String, Object> row = new HashMap<>();
        row.put("phone", "13812345678");
        row.put("name", "keep");

        List<Map<String, Object>> maskedList =
                fieldMaskService.applyMaskingForList(model, List.of(row), 990_000_002L);
        assertEquals("138****5678", maskedList.get(0).get("phone"));
        assertEquals("keep", maskedList.get(0).get("name")); // unconfigured field untouched

        Map<String, Object> detailRow = new HashMap<>();
        detailRow.put("phone", "13812345678");
        Map<String, Object> maskedDetail =
                fieldMaskService.applyMaskingForDetail(model, detailRow, 990_000_002L);
        assertEquals("138****5678", maskedDetail.get("phone"));
    }

    @Test
    @DisplayName("disabled config does not mask")
    void disabledConfigNoMasking() {
        fieldMaskService.saveConfig(config("phone", "phone", false));

        Map<String, Object> row = new HashMap<>();
        row.put("phone", "13812345678");
        List<Map<String, Object>> result =
                fieldMaskService.applyMaskingForList(model, List.of(row), 990_000_002L);
        assertEquals("13812345678", result.get(0).get("phone"));
    }

    @Test
    @DisplayName("runtime masking reuses enabled config cache through the facade")
    void runtimeMaskingReusesEnabledConfigCache() {
        Map<String, Object> row = Map.of("phone", "13812345678");

        fieldMaskService.applyMaskingForList(model, List.of(row), 990_000_002L);
        clearInvocations(fieldMaskConfigMapper);

        fieldMaskService.applyMaskingForList(model, List.of(row), 990_000_002L);

        verifyNoInteractions(fieldMaskConfigMapper);
    }
}
