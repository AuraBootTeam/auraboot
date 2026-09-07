package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DDLPreviewResult;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.MetaModelPublishReplayRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for the {@link MetaModelServiceImpl} metadata-validation,
 * model-data validation, and publish-governance surface (the methods with zero prior
 * test references: validateModelMetadata / validateModelData / previewPublishDDL /
 * replayPublishImpact) plus the existence checks. Single scenario, run-unique model
 * codes with a family purge so re-runs never collide on shared-tenant rows.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("MetaModelServiceImpl Coverage IT — metadata validation + publish governance")
class MetaModelServiceImplCoverageIT extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("metadata validation, existence checks, DDL preview, replay report, model-data validation")
    void metadataAndGovernanceScenario() {
        setupTenantContext();
        applyTestMetaContext();
        purgeFamily();
        Long tenantId = getTestTenant().getId();
        long suffix = System.currentTimeMillis();
        String code = "mmsit_" + suffix;

        try {
            // ── validateModelMetadata on an unknown model fails closed with a reason ──
            var unknown = metaModelService.validateModelMetadata(code);
            assertFalse(unknown.getValid());
            assertTrue(unknown.getErrors().contains("Model not found: " + code));

            // ── a real physical definition validates clean and feeds the existence checks ──
            metaModelService.saveDefinition(ModelDefinition.builder()
                    .code(code)
                    .displayName("MMSIT model " + code)
                    .sourceType("physical")
                    .tableName("mt_" + code)
                    .primaryKey("id")
                    .capabilities(com.auraboot.framework.meta.dto.ModelCapabilities.fullPhysical())
                    .build());

            var validation = metaModelService.validateModelMetadata(code);
            // Physical auto-fields (id/pid/created_at/...) carry no dataType mapping yet,
            // so validateFields must flag each of them and the summary must flip to failed.
            assertEquals(Boolean.FALSE, validation.getValid());
            assertTrue(validation.getErrors().stream()
                    .anyMatch(e -> e.contains("Data type mapping is required for field: id")),
                    "errors=" + validation.getErrors());
            assertEquals("Model validation failed", validation.getSummary());

            assertTrue(metaModelService.isModelExists(code));
            assertFalse(metaModelService.isModelExists("mmsit_never_" + suffix));
            assertTrue(metaModelService.isFieldExists(code, "id"));
            assertFalse(metaModelService.isFieldExists(code, "no_such_field"));

            MetaModelDTO dto = metaModelService.findByCode(code);
            assertNotNull(dto.getPid());

            // ── previewPublishDDL reports governance over the draft schema ──
            DDLPreviewResult preview = metaModelService.previewPublishDDL(dto.getPid());
            assertNotNull(preview);
            assertNotNull(preview.getGovernance());
            assertEquals(code, preview.getGovernance().getModelCode());

            // ── replayPublishImpact returns a fully-shaped report ──
            MetaModelPublishReplayRequest request = new MetaModelPublishReplayRequest();
            request.setExecuteAutomated(Boolean.FALSE);
            var report = metaModelService.replayPublishImpact(dto.getPid(), request);
            assertNotNull(report);
            assertEquals(code, report.getModelCode());
            assertTrue(report.getTotalCount() >= 0);
            assertNotNull(report.getGovernance());
            assertNotNull(report.getGeneratedAt());

            // ── validateModelData: shape, format, uniqueness, and modelType rules ──
            Map<String, Object> good = new HashMap<>();
            good.put("code", code + "_v2");
            good.put("displayName", "Another model");
            good.put("modelType", "entity");
            var goodResult = metaModelService.validateModelData(good);
            assertEquals(Boolean.TRUE, goodResult.get("valid"), goodResult.toString());

            Map<String, Object> missingCode = new HashMap<>();
            missingCode.put("displayName", "No code");
            var missingResult = metaModelService.validateModelData(missingCode);
            assertEquals(Boolean.FALSE, missingResult.get("valid"));
            assertTrue(((Map<?, ?>) missingResult.get("errors")).containsKey("code"));

            Map<String, Object> badFormat = Map.of("code", "1bad_code", "displayName", "x");
            assertTrue(((Map<?, ?>) metaModelService.validateModelData(badFormat).get("errors")).containsKey("code"));

            Map<String, Object> duplicate = Map.of("code", code, "displayName", "dup");
            var dupResult = metaModelService.validateModelData(duplicate);
            assertEquals(Boolean.FALSE, dupResult.get("valid"));
            assertTrue(((Map<?, ?>) dupResult.get("errors")).get("code").toString().contains("已存在"));

            Map<String, Object> badType = Map.of("code", code + "_v3", "displayName", "x", "modelType", "teleport");
            assertTrue(((Map<?, ?>) metaModelService.validateModelData(badType).get("errors")).containsKey("modelType"));
        } finally {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + code);
            } catch (Exception ignored) {
            }
            purgeFamily();
            MetaContext.clear();
        }
    }

    private void purgeFamily() {
        Long tenantId = getTestTenant().getId();
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'mmsit\\_%'",
                String.class, tenantId);
        for (String code : codes) {
            jdbcTemplate.update("DROP TABLE IF EXISTS mt_" + code);
        }
        jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                        + "(SELECT id FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'mmsit\\_%')", tenantId);
        jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE tenant_id = ? "
                        + "AND (code LIKE 'mmsit\\_%' OR id NOT IN (SELECT field_id FROM ab_meta_model_field_binding) "
                        + "OR id IN (SELECT b.field_id FROM ab_meta_model_field_binding b "
                        + "JOIN ab_meta_model m ON m.id = b.model_id "
                        + "WHERE m.tenant_id = ? AND m.code LIKE 'mmsit\\_%'))", tenantId, tenantId);
        jdbcTemplate.update("DELETE FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'mmsit\\_%'", tenantId);
    }
}
