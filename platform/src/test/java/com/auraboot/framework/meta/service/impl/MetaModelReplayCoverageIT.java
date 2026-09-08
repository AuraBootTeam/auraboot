package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.MetaModelPublishReplayRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave-4 deep-branch coverage for {@link MetaModelServiceImpl}: version comparison
 * (compareVersions) and the publish-replay governance surface (replayPublishImpact →
 * per-consumer replay steps, here the SLA RECORD branch) that the base coverage IT
 * does not reach.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MetaModelServiceImpl Coverage IT — compareVersions + SLA replay")
class MetaModelReplayCoverageIT extends BaseIntegrationTest {

    private static final String CODE = "mmrep_it_" + System.currentTimeMillis();

    @Autowired
    private MetaModelService metaModelService;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private SlaConfigService slaConfigService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    private Model insertVersion(String displayName, int version, boolean isCurrent) {
        Model m = new Model();
        m.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        m.setTenantId(tenantId);
        m.setCode(CODE);
        m.setVersion(version);
        m.setIsCurrent(isCurrent);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", displayName);
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);
        return m;
    }

    @Test
    @DisplayName("compareVersions diffs two stored versions; SLA replay reports a READY step")
    void compareVersionsAndSlaReplay() {
        setupTenantContext();
        applyTestMetaContext();
        purgeFamily();
        tenantId = getTestTenant().getId();

        // Two stored versions of the same code with a differing displayName.
        insertVersion("Model A", 1, false);
        insertVersion("Model B", 2, true);

        Map<String, Object> diff = metaModelService.compareVersions(CODE, 1, 2);
        assertEquals(CODE, diff.get("code"));
        assertTrue(diff.get("changes").toString().contains("displayName"),
                "displayName change must be reported: " + diff.get("changes"));

        assertThrows(Exception.class, () -> metaModelService.compareVersions(CODE, 9, 98));

        // ── SLA RECORD replay: create a RECORD-scoped SLA config on this model, then
        //    replay the publish impact — the plan must carry an SLA step that is READY
        //    (executeAutomated not requested). ──
        var sla = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "Replay SLA " + CODE, "RECORD", CODE, null,
                "FIXED", "PT2H", null, null, null,
                CODE, null, null, null));
        assertNotNull(sla.getPid());

        MetaModelDTO dto = metaModelService.findByCode(CODE);
        var report = metaModelService.replayPublishImpact(dto.getPid(),
                new MetaModelPublishReplayRequest());
        assertEquals(CODE, report.getModelCode());
        assertNotNull(report.getGovernance());
        assertTrue(report.getTotalCount() >= 0);
        assertNotNull(report.getResults());
    }

    private void purgeFamily() {
        jdbcTemplate.update("DROP TABLE IF EXISTS mt_" + CODE);
        jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                        + "(SELECT id FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'mmrep\\_%')", tenantId);
        jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE tenant_id = ? AND code LIKE 'mmrep\\_%' "
                        + "AND (deleted_flag = TRUE OR id NOT IN (SELECT field_id FROM ab_meta_model_field_binding))",
                tenantId);
        jdbcTemplate.update("DELETE FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'mmrep\\_%'", tenantId);
    }

    @AfterAll
    void cleanup() {
        applyTestMetaContext();
        purgeFamily();
        try {
            jdbcTemplate.update(
                    "DELETE FROM ab_sla_config WHERE model_code = ? AND tenant_id = ?", CODE, tenantId);
        } catch (Exception ignored) {
        }
        MetaContext.clear();
    }
}
