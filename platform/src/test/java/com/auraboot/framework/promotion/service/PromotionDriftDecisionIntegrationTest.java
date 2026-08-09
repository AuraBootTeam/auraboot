package com.auraboot.framework.promotion.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceService;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.promotion.dto.DryRunResult;
import com.auraboot.framework.promotion.dto.PromotionDriftDecisionRequest;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-PostgreSQL proof that promotion cannot silently erase a target-local release. */
class PromotionDriftDecisionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PromotionService promotionService;
    @Autowired
    private EnvironmentService environmentService;
    @Autowired
    private EnvironmentMapper environmentMapper;
    @Autowired
    private PageSchemaMapper pageSchemaMapper;
    @Autowired
    private AuthoringWorkspaceService workspaceService;
    @Autowired
    private AuthoringGovernanceService governanceService;
    @Autowired
    private AuthoringCapabilityRegistry capabilityRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearEnvironment() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void explicitOverwriteSupersedesTargetOverrideAndPreservesDecisionHistory() throws Exception {
        Long sourceEnv = createEnvironment("drift_source_");
        Long targetEnv = createEnvironment("drift_target_");
        String pageKey = "drift_orders_" + shortId();
        PageSchema source = insertApplicationPage(
                sourceEnv, pageKey, "compact", "plugin-orders");
        PageSchema target = insertApplicationPage(
                targetEnv, pageKey, "normal", "plugin-orders");
        PublishedOverride published = publishTargetOverride(targetEnv, target);

        PromotionResponse draft = promotionService.create(
                request(sourceEnv, targetEnv, source.getPid()),
                testTenant.getId(), testUser.getId());
        DryRunResult detected = promotionService.validate(draft.getPid(), testTenant.getId());

        assertThat(detected.isValid()).isFalse();
        assertThat(detected.getConflicts()).isEmpty();
        assertThat(detected.getDrifts()).hasSize(1);
        DryRunResult.Drift drift = detected.getDrifts().getFirst();
        assertThat(drift.getKind()).isEqualTo("TENANT_OVERRIDE");
        assertThat(drift.getStatus()).isEqualTo("PENDING");
        assertThat(drift.isApplyReady()).isFalse();
        assertThat(drift.getOptions())
                .containsExactly("REBASE", "BACKPORT", "KEEP_OVERRIDE", "OVERWRITE");

        PromotionResponse rebaseRecorded = promotionService.resolveDrift(
                draft.getPid(), drift.getUnitPid(),
                decision(drift.getFingerprint(), "REBASE", "先进入专业三方合并"),
                testTenant.getId(), testUser.getId());
        assertThat(rebaseRecorded.getStatus()).isEqualTo("DRAFT");
        assertThat(rebaseRecorded.getDryRunResult().getDrifts().getFirst().getDecision())
                .isEqualTo("REBASE");
        assertThat(rebaseRecorded.getDryRunResult().getDrifts().getFirst().isApplyReady())
                .isFalse();
        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "must remain blocked"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");

        PromotionResponse overwriteRecorded = promotionService.resolveDrift(
                draft.getPid(), drift.getUnitPid(),
                decision(drift.getFingerprint(), "OVERWRITE", "源版本已正式吸收现场修复"),
                testTenant.getId(), testUser.getId());
        assertThat(overwriteRecorded.getStatus()).isEqualTo("VALIDATED");
        assertThat(overwriteRecorded.getDryRunResult().isValid()).isTrue();
        assertThat(overwriteRecorded.getDryRunResult().getDrifts().getFirst().isApplyReady())
                .isTrue();

        PromotionResponse applied = promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "ship governed overwrite");
        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getUnits().getFirst().getDriftStatus()).isEqualTo("APPLIED");
        assertThat(applied.getUnits().getFirst().getDriftDecision()).isEqualTo("OVERWRITE");

        MetaContext.setEnvironmentId(targetEnv);
        PageSchema current = pageSchemaMapper.selectOne(new QueryWrapper<PageSchema>()
                .eq("page_key", pageKey)
                .eq("is_current", true)
                .eq("deleted_flag", false));
        assertThat(current.getPid()).isNotEqualTo(target.getPid());
        assertThat(current.getOwnershipScope()).isEqualTo("APPLICATION");
        assertThat(current.getPluginPid()).isEqualTo("plugin-orders");
        assertThat(objectMapper.readTree(current.getBlocks()))
                .isEqualTo(objectMapper.readTree(source.getBlocks()));

        assertThat(statusOf("ab_authoring_release", published.releasePid()))
                .isEqualTo("SUPERSEDED");
        assertThat(statusOf("ab_authoring_tenant_override", published.overridePid()))
                .isEqualTo("SUPERSEDED");
        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT event_type, decision, reason_code
                FROM ab_promotion_drift_event
                WHERE tenant_id = ? AND promotion_unit_id = (
                    SELECT id FROM ab_promotion_unit WHERE pid = ?)
                ORDER BY id
                """, testTenant.getId(), drift.getUnitPid());
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("DETECTED", "DECIDED", "DECIDED", "APPLIED");
        assertThat(events).extracting(row -> row.get("decision"))
                .containsExactly(null, "REBASE", "OVERWRITE", "OVERWRITE");
    }

    @Test
    void staleFingerprintRejectsDecisionWithoutReplacingPriorDetection() throws Exception {
        Long sourceEnv = createEnvironment("drift_stale_source_");
        Long targetEnv = createEnvironment("drift_stale_target_");
        String pageKey = "drift_stale_" + shortId();
        PageSchema source = insertApplicationPage(
                sourceEnv, pageKey, "compact", "plugin-stale");
        PageSchema target = insertApplicationPage(
                targetEnv, pageKey, "normal", "plugin-stale");
        publishTargetOverride(targetEnv, target);
        PromotionResponse draft = promotionService.create(
                request(sourceEnv, targetEnv, source.getPid()),
                testTenant.getId(), testUser.getId());
        DryRunResult.Drift detected = promotionService
                .validate(draft.getPid(), testTenant.getId()).getDrifts().getFirst();

        MetaContext.setEnvironmentId(sourceEnv);
        source.setTitle("{\"en\":\"Changed after validation\"}");
        pageSchemaMapper.updateById(source);

        assertThatThrownBy(() -> promotionService.resolveDrift(
                draft.getPid(), detected.getUnitPid(),
                decision(detected.getFingerprint(), "OVERWRITE", "stale decision"),
                testTenant.getId(), testUser.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion.drift.fingerprint-stale");
        PromotionResponse synchronizedPromotion = promotionService.getByPid(
                draft.getPid(), testTenant.getId());
        assertThat(synchronizedPromotion.getStatus()).isEqualTo("DRAFT");
        assertThat(synchronizedPromotion.getUnits().getFirst().getDriftStatus())
                .isEqualTo("PENDING");
        assertThat(synchronizedPromotion.getDryRunResult().getDrifts().getFirst().getFingerprint())
                .isNotEqualTo(detected.getFingerprint());

        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT event_type, decision FROM ab_promotion_drift_event
                WHERE promotion_unit_id = (SELECT id FROM ab_promotion_unit WHERE pid = ?)
                ORDER BY id
                """, detected.getUnitPid());
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("DETECTED", "STALE", "DETECTED");
        assertThat(events).extracting(row -> row.get("decision"))
                .containsOnlyNulls();
    }

    @Test
    void applyRecheckReturnsPromotionToDraftWhenResolvedFingerprintDrifts() {
        Long sourceEnv = createEnvironment("das_src_");
        Long targetEnv = createEnvironment("das_tgt_");
        String pageKey = "drift_apply_stale_" + shortId();
        PageSchema source = insertApplicationPage(
                sourceEnv, pageKey, "compact", "plugin-apply-stale");
        PageSchema target = insertApplicationPage(
                targetEnv, pageKey, "normal", "plugin-apply-stale");
        PublishedOverride published = publishTargetOverride(targetEnv, target);
        PromotionResponse draft = promotionService.create(
                request(sourceEnv, targetEnv, source.getPid()),
                testTenant.getId(), testUser.getId());
        DryRunResult.Drift drift = promotionService
                .validate(draft.getPid(), testTenant.getId()).getDrifts().getFirst();
        promotionService.resolveDrift(
                draft.getPid(), drift.getUnitPid(),
                decision(drift.getFingerprint(), "OVERWRITE", "旧指纹下的覆盖决策"),
                testTenant.getId(), testUser.getId());

        MetaContext.setEnvironmentId(sourceEnv);
        source.setTitle("{\"en\":\"Source changed before apply\"}");
        pageSchemaMapper.updateById(source);

        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "must revalidate"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion.drift.decision-not-apply-ready");
        PromotionResponse invalidated = promotionService.getByPid(
                draft.getPid(), testTenant.getId());
        assertThat(invalidated.getStatus()).isEqualTo("DRAFT");
        assertThat(invalidated.getDryRunResult()).isNull();
        assertThat(invalidated.getUnits().getFirst().getDriftStatus()).isEqualTo("PENDING");
        assertThat(invalidated.getUnits().getFirst().getDriftDecision()).isNull();
        assertThat(statusOf("ab_authoring_release", published.releasePid())).isEqualTo("ACTIVE");
        assertThat(statusOf("ab_authoring_tenant_override", published.overridePid()))
                .isEqualTo("ACTIVE");
    }

    private PublishedOverride publishTargetOverride(Long targetEnv, PageSchema target) {
        MetaContext.setEnvironmentId(targetEnv);
        SessionView opened = workspaceService.open(new OpenSessionRequest(target.getPid(), null));
        SessionView changed = workspaceService.applyStudio(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        opened.revision(), "table-1", "/dataSource", PatchOperation.ADD,
                        objectMapper.valueToTree(Map.of("model", "payments")),
                        capabilityRegistry.find("table").orElseThrow().checksum())).session();
        governanceService.prepare(opened.sessionPid(), new RevisionRequest(changed.revision()));
        governanceService.submit(opened.sessionPid(), new RevisionRequest(changed.revision()));
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 200_000,
                    "promotion-drift-reviewer", "promotion-drift-reviewer");
            MetaContext.setEnvironmentId(targetEnv);
            governanceService.approve(
                    opened.changeSetPid(),
                    new ReviewRequest(changed.revision(), "drift checked"));
        } finally {
            applyTestMetaContext();
            MetaContext.setEnvironmentId(targetEnv);
        }
        var release = governanceService.publish(
                opened.changeSetPid(), new RevisionRequest(changed.revision()));
        return new PublishedOverride(release.releasePid(), opened.ownership().overridePid());
    }

    private PageSchema insertApplicationPage(
            Long envId,
            String pageKey,
            String density,
            String pluginPid) {
        ensureModel("test_model");
        ensureModel("payments");
        Long prior = MetaContext.getCurrentEnvironmentId();
        MetaContext.setEnvironmentId(envId);
        try {
            String pid = UniqueIdGenerator.generate();
            PageSchema page = new PageSchema();
            page.setPid(pid);
            page.setTenantId(testTenant.getId());
            page.setPageKey(pageKey);
            page.setModelCode("test_model");
            page.setName("promotion_drift_" + pid);
            page.setKind("list");
            page.setProfile("admin");
            page.setSchemaVersion(4);
            page.setTitle("{\"en\":\"Orders\"}");
            page.setLayout("{\"type\":\"stack\"}");
            page.setBlocks("[{\"id\":\"table-1\",\"blockType\":\"table\","
                    + "\"props\":{\"density\":\"" + density + "\"}}]");
            page.setStatus("published");
            page.setVersion(1);
            page.setSemver("1.0.0");
            page.setRowVersion(1);
            page.setIsCurrent(true);
            page.setIsTemplate(false);
            page.setPluginPid(pluginPid);
            page.setOwnershipRef(pluginPid);
            page.setDeletedFlag(false);
            pageSchemaMapper.insert(page);
            return page;
        } finally {
            MetaContext.setEnvironmentId(prior);
        }
    }

    private void ensureModel(String code) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_meta_model
                WHERE tenant_id = ? AND code = ? AND is_current = TRUE
                  AND deleted_flag = FALSE
                """, Integer.class, testTenant.getId(), code);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, version, is_current,
                    row_version, status, deleted_flag)
                VALUES (?, ?, ?, ?, 1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), testTenant.getId(), code, "mt_" + code);
    }

    private Long createEnvironment(String prefix) {
        String code = prefix + shortId();
        EnvironmentRequest request = new EnvironmentRequest();
        request.setCode(code);
        request.setName(code);
        request.setIsDefault(false);
        request.setSortOrder(0);
        var created = environmentService.create(
                request, testTenant.getId(), testUser.getId());
        Environment environment = environmentMapper.selectOne(
                new QueryWrapper<Environment>()
                        .eq("pid", created.getPid())
                        .eq("tenant_id", testTenant.getId()));
        return environment.getId();
    }

    private PromotionRequest request(Long sourceEnv, Long targetEnv, String pagePid) {
        PromotionRequest request = new PromotionRequest();
        request.setSourceEnvId(sourceEnv);
        request.setTargetEnvId(targetEnv);
        PromotionRequest.PromotionUnitDto unit = new PromotionRequest.PromotionUnitDto();
        unit.setResourceType("PAGE_SCHEMA");
        unit.setResourcePid(pagePid);
        unit.setSortOrder(0);
        request.setUnits(List.of(unit));
        return request;
    }

    private PromotionDriftDecisionRequest decision(
            String fingerprint,
            String decision,
            String reason) {
        PromotionDriftDecisionRequest request = new PromotionDriftDecisionRequest();
        request.setExpectedFingerprint(fingerprint);
        request.setDecision(decision);
        request.setReason(reason);
        return request;
    }

    private String statusOf(String table, String pid) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + table + " WHERE pid = ?", String.class, pid);
    }

    private static String shortId() {
        return UniqueIdGenerator.generate().toLowerCase();
    }

    private record PublishedOverride(String releasePid, String overridePid) {
    }
}
