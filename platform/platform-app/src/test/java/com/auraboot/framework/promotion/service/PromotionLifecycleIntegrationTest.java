package com.auraboot.framework.promotion.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.dto.EnvironmentResponse;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.promotion.dto.DryRunResult;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;
import com.auraboot.framework.promotion.service.PromotionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for PromotionService — create + validate paths (task #7 PoC).
 * Apply / reject (#9 + UX phase 2) excluded.
 */
class PromotionLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    // ---------- create() ----------

    @Test
    void create_validInputs_returnsDraftStatus() {
        Long sourceEnv = createEnv("src_" + shortId());
        Long targetEnv = createEnv("tgt_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "create_basic_" + shortId());

        PromotionResponse promo = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        assertThat(promo.getStatus()).isEqualTo("DRAFT");
        assertThat(promo.getSourceEnvId()).isEqualTo(sourceEnv);
        assertThat(promo.getTargetEnvId()).isEqualTo(targetEnv);
        assertThat(promo.getUnits()).hasSize(1);
        assertThat(promo.getUnits().get(0).getResourcePid()).isEqualTo(sourcePage.getPid());
        // sourceVersion auto-captured from source page when not provided in request
        assertThat(promo.getUnits().get(0).getSourceVersion()).isEqualTo(sourcePage.getVersion());
    }

    @Test
    void create_sameSourceAndTarget_throws() {
        Long envId = createEnv("solo_" + shortId());
        PageSchema page = insertPage(envId, "page_" + shortId());

        assertThatThrownBy(() -> promotionService.create(
                buildRequest(envId, envId, page.getPid()),
                testTenant.getId(), testUser.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void create_emptyUnits_throws() {
        Long s = createEnv("s_" + shortId());
        Long t = createEnv("t_" + shortId());
        PromotionRequest req = new PromotionRequest();
        req.setSourceEnvId(s);
        req.setTargetEnvId(t);
        req.setUnits(java.util.Collections.emptyList());

        assertThatThrownBy(() -> promotionService.create(req, testTenant.getId(), testUser.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one unit");
    }

    @Test
    void create_unsupportedResourceType_throws() {
        Long s = createEnv("s2_" + shortId());
        Long t = createEnv("t2_" + shortId());
        PromotionRequest req = buildRequest(s, t, "doesnt-matter");
        req.getUnits().get(0).setResourceType("MODEL");  // not supported in PoC

        assertThatThrownBy(() -> promotionService.create(req, testTenant.getId(), testUser.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported resourceType");
    }

    // ---------- validate() ----------

    @Test
    void validate_noConflict_transitionsToValidated() {
        Long sourceEnv = createEnv("src_v_" + shortId());
        Long targetEnv = createEnv("tgt_v_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "v_clean_" + shortId());
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        DryRunResult result = promotionService.validate(draft.getPid(), testTenant.getId());

        assertThat(result.isValid()).isTrue();
        assertThat(result.getConflicts()).isEmpty();
        assertThat(result.getValidatedAt()).isNotNull();

        PromotionResponse reload = promotionService.getByPid(draft.getPid(), testTenant.getId());
        assertThat(reload.getStatus()).isEqualTo("VALIDATED");
        assertThat(reload.getDryRunAt()).isNotNull();
        assertThat(reload.getDryRunResult()).isNotNull();
        assertThat(reload.getDryRunResult().isValid()).isTrue();
    }

    @Test
    void validate_targetHasDifferentContent_recordsConflictAndStaysDraft() {
        Long sourceEnv = createEnv("src_c_" + shortId());
        Long targetEnv = createEnv("tgt_c_" + shortId());

        String pageKey = "v_conflict_" + shortId();
        // Source: page with blocks=[{a:1}]
        PageSchema sourcePage = insertPageWithBlocks(sourceEnv, pageKey, "[{\"a\":1}]");
        // Target: page with same page_key but different blocks
        insertPageWithBlocks(targetEnv, pageKey, "[{\"a\":2}]");

        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        DryRunResult result = promotionService.validate(draft.getPid(), testTenant.getId());

        assertThat(result.isValid()).isFalse();
        assertThat(result.getConflicts()).hasSize(1);
        DryRunResult.Conflict conflict = result.getConflicts().get(0);
        assertThat(conflict.getResourceType()).isEqualTo("PAGE_SCHEMA");
        assertThat(conflict.getResourcePid()).isEqualTo(sourcePage.getPid());
        assertThat(conflict.getReason()).contains(pageKey);

        // Field-level diff is populated and points at the actual change
        assertThat(conflict.getDiff()).isNotEmpty();
        com.auraboot.framework.promotion.diff.SemanticDiffEntry firstChange = conflict.getDiff().get(0);
        assertThat(firstChange.getPath()).isEqualTo("blocks[0].a");
        assertThat(firstChange.getOp())
                .isEqualTo(com.auraboot.framework.promotion.diff.SemanticDiffEntry.Op.MODIFY);
        assertThat(firstChange.getOldValue().toString()).isEqualTo("1");
        assertThat(firstChange.getNewValue().toString()).isEqualTo("2");

        PromotionResponse reload = promotionService.getByPid(draft.getPid(), testTenant.getId());
        // Conflicts → status stays at (or rolls back to) DRAFT — apply must not be permitted yet
        assertThat(reload.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void validate_targetHasIdenticalContent_isNoConflict() {
        Long sourceEnv = createEnv("src_id_" + shortId());
        Long targetEnv = createEnv("tgt_id_" + shortId());

        String pageKey = "v_identical_" + shortId();
        PageSchema sourcePage = insertPageWithBlocks(sourceEnv, pageKey, "[{\"x\":42}]");
        // Same content in target — promotion is a no-op, no conflict
        insertPageWithBlocks(targetEnv, pageKey, "[{\"x\":42}]");

        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        DryRunResult result = promotionService.validate(draft.getPid(), testTenant.getId());

        assertThat(result.isValid()).isTrue();
        assertThat(result.getConflicts()).isEmpty();
    }

    @Test
    void validate_targetMissing_isNoConflict() {
        Long sourceEnv = createEnv("src_m_" + shortId());
        Long targetEnv = createEnv("tgt_m_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "v_missing_" + shortId());
        // Target env has nothing — first-time promote, no conflict

        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        DryRunResult result = promotionService.validate(draft.getPid(), testTenant.getId());
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void validate_alreadyValidated_isIdempotent() {
        Long sourceEnv = createEnv("src_re_" + shortId());
        Long targetEnv = createEnv("tgt_re_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "v_re_" + shortId());
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        promotionService.validate(draft.getPid(), testTenant.getId());
        DryRunResult second = promotionService.validate(draft.getPid(), testTenant.getId());

        assertThat(second.isValid()).isTrue();
        PromotionResponse reload = promotionService.getByPid(draft.getPid(), testTenant.getId());
        assertThat(reload.getStatus()).isEqualTo("VALIDATED");
    }

    // ---------- helpers ----------

    private Long createEnv(String code) {
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode(code);
        req.setName(code);
        req.setIsDefault(false);
        req.setSortOrder(0);
        EnvironmentResponse env = environmentService.create(req, testTenant.getId(), testUser.getId());
        Environment probe = environmentMapper.selectOne(
                new QueryWrapper<Environment>().eq("pid", env.getPid()).eq("tenant_id", testTenant.getId()));
        return probe.getId();
    }

    private PageSchema insertPage(Long envId, String pageKey) {
        return insertPageWithBlocks(envId, pageKey, "[]");
    }

    private PageSchema insertPageWithBlocks(Long envId, String pageKey, String blocksJson) {
        Long prior = MetaContext.getCurrentEnvironmentId();
        MetaContext.setEnvironmentId(envId);
        try {
            String unique = UniqueIdGenerator.generate();
            PageSchema p = new PageSchema();
            p.setPid(unique);
            p.setTenantId(testTenant.getId());
            p.setPageKey(pageKey);
            p.setModelCode("test_model");
            p.setName("test_" + unique);
            p.setKind("list");
            p.setProfile("admin");
            p.setSchemaVersion(2);
            p.setBlocks(blocksJson);
            p.setStatus("draft");
            p.setVersion(1);
            p.setIsCurrent(true);
            p.setRowVersion(1);
            pageSchemaMapper.insert(p);
            return p;
        } finally {
            MetaContext.setEnvironmentId(prior);
        }
    }

    private PromotionRequest buildRequest(Long sourceEnv, Long targetEnv, String pagePid) {
        PromotionRequest req = new PromotionRequest();
        req.setSourceEnvId(sourceEnv);
        req.setTargetEnvId(targetEnv);
        PromotionRequest.PromotionUnitDto unit = new PromotionRequest.PromotionUnitDto();
        unit.setResourceType("PAGE_SCHEMA");
        unit.setResourcePid(pagePid);
        unit.setSortOrder(0);
        req.setUnits(List.of(unit));
        return req;
    }

    private static String shortId() {
        return UniqueIdGenerator.generate().toLowerCase();
    }
}
