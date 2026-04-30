package com.auraboot.framework.promotion.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.promotion.dao.entity.Promotion;
import com.auraboot.framework.promotion.dao.mapper.PromotionMapper;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for PromotionService.apply (#9). Covers happy path, four-eyes guard,
 * stale dry-run, missing-source rollback (FAILED status), and terminal immutability.
 */
class PromotionApplyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private PromotionMapper promotionMapper;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void apply_validatedNoConflict_insertsTargetPageAndTransitionsToApplied() {
        Long sourceEnv = createEnv("apply_src_" + shortId());
        Long targetEnv = createEnv("apply_tgt_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv,
                "p_app_" + shortId(),
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"name\"}]}]");

        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        // Apply with same user (no four-eyes since target unlocked)
        PromotionResponse applied = promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "ship to staging");

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getAppliedAt()).isNotNull();
        assertThat(applied.getAppliedBy()).isEqualTo(testUser.getId());
        assertThat(applied.getAppliedReason()).isEqualTo("ship to staging");

        // Target env now has the page with same blocks (compare JSON tree, not raw text —
        // PG canonicalizes JSONB whitespace).
        MetaContext.setEnvironmentId(targetEnv);
        PageSchema targetPage = pageSchemaMapper.selectAnyByPageKey(sourcePage.getPageKey());
        assertThat(targetPage).isNotNull();
        assertThat(targetPage.getEnvId()).isEqualTo(targetEnv);
        assertThat(targetPage.getVersion()).isEqualTo(1);
        assertThat(targetPage.getIsCurrent()).isTrue();
        assertSameJson(targetPage.getBlocks(), sourcePage.getBlocks());
    }

    private static void assertSameJson(String actualJson, String expectedJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            assertThat(mapper.readTree(actualJson)).isEqualTo(mapper.readTree(expectedJson));
        } catch (Exception e) {
            throw new AssertionError("JSON parse failed: " + e.getMessage(), e);
        }
    }

    @Test
    void apply_existingTargetPage_bumpsVersionAndPriorBecomesNotCurrent() {
        Long sourceEnv = createEnv("apply_src2_" + shortId());
        Long targetEnv = createEnv("apply_tgt2_" + shortId());

        String pageKey = "p_bump_" + shortId();
        // Source: blocks A
        PageSchema sourcePage = insertPage(sourceEnv, pageKey, "[{\"a\":1}]");
        // Target: identical content (so validate is clean) — apply still creates v2
        insertPage(targetEnv, pageKey, "[{\"a\":1}]");

        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        promotionService.apply(draft.getPid(), testTenant.getId(), testUser.getId(), "bump");

        // Target env should have 2 versions of the page; v1 is_current=false, v2 is_current=true
        MetaContext.setEnvironmentId(targetEnv);
        QueryWrapper<PageSchema> qw = new QueryWrapper<>();
        qw.eq("page_key", pageKey)
                .eq("tenant_id", testTenant.getId())
                .eq("deleted_flag", false)
                .orderByAsc("version");
        List<PageSchema> versions = pageSchemaMapper.selectList(qw);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getVersion()).isEqualTo(1);
        assertThat(versions.get(0).getIsCurrent()).isFalse();
        assertThat(versions.get(1).getVersion()).isEqualTo(2);
        assertThat(versions.get(1).getIsCurrent()).isTrue();
    }

    @Test
    void apply_lockedTargetSameAuthor_throwsFourEyes() {
        Long sourceEnv = createEnv("apply_lock_src_" + shortId());
        Long targetEnv = createEnv("apply_lock_tgt_" + shortId());
        // Lock target
        environmentService.lock(envPidById(targetEnv), testTenant.getId(), testUser.getId(), "freeze");

        PageSchema sourcePage = insertPage(sourceEnv, "p_4e_" + shortId(), "[{\"a\":1}]");
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        // Same user attempts apply → blocked by four-eyes
        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "trying anyway"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Four-eyes");
    }

    @Test
    void apply_lockedTargetReasonMissing_throws() {
        Long sourceEnv = createEnv("apply_reason_src_" + shortId());
        Long targetEnv = createEnv("apply_reason_tgt_" + shortId());
        environmentService.lock(envPidById(targetEnv), testTenant.getId(), testUser.getId(), "freeze");

        PageSchema sourcePage = insertPage(sourceEnv, "p_rs_" + shortId(), "[{\"a\":1}]");
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        // Different approver but blank reason → rejected
        Long otherApprover = testUser.getId() + 1;
        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), otherApprover, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reason is required");
    }

    @Test
    void apply_staleDryRun_throws() {
        Long sourceEnv = createEnv("apply_stale_src_" + shortId());
        Long targetEnv = createEnv("apply_stale_tgt_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "p_st_" + shortId(), "[{\"a\":1}]");
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        // Backdate dry_run_at by 25h
        Date stale = Date.from(java.time.Instant.now().minus(java.time.Duration.ofHours(25)));
        UpdateWrapper<Promotion> uw = new UpdateWrapper<>();
        uw.eq("pid", draft.getPid()).set("dry_run_at", stale);
        promotionMapper.update(null, uw);

        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "ship"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void apply_sourceMissingAtApplyTime_throwsAndRollsBackTargetWrites() {
        Long sourceEnv = createEnv("apply_fail_src_" + shortId());
        Long targetEnv = createEnv("apply_fail_tgt_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "p_fail_" + shortId(), "[{\"a\":1}]");
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        // Soft-delete the source page after validate (simulates someone removing it)
        UpdateWrapper<PageSchema> uw = new UpdateWrapper<>();
        uw.eq("id", sourcePage.getId()).set("deleted_flag", true);
        MetaContext.setEnvironmentId(sourceEnv);
        pageSchemaMapper.update(null, uw);
        MetaContext.setEnvironmentId(null);

        // Apply throws (markFailedInNewTx runs in REQUIRES_NEW which doesn't see this test's
        // uncommitted promotion row — that DB-level FAILED-status assertion is verified in a
        // separate non-rollback test, deferred. Here we verify the rollback semantic: the
        // exception was raised and target env saw NO writes).
        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "go"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Source page missing");

        // Target env should NOT have the page (apply tx rolled back)
        MetaContext.setEnvironmentId(targetEnv);
        PageSchema targetPage = pageSchemaMapper.selectAnyByPageKey(sourcePage.getPageKey());
        assertThat(targetPage).isNull();
    }

    @Test
    void apply_lastDryRunHadConflicts_throws() {
        Long sourceEnv = createEnv("apply_inv_src_" + shortId());
        Long targetEnv = createEnv("apply_inv_tgt_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "p_inv_" + shortId(), "[{\"a\":1}]");
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());

        // Manually force VALIDATED status with an "invalid" dry-run result (simulates a bug
        // or someone tampering — defensive check). Use entity update so the JSONB type handler
        // is honored.
        Promotion forced = promotionMapper.selectOne(
                new QueryWrapper<Promotion>().eq("pid", draft.getPid()).eq("tenant_id", testTenant.getId()));
        forced.setStatus("VALIDATED");
        forced.setDryRunAt(new Date());
        forced.setDryRunResult(
                "{\"valid\":false,\"validatedAt\":\"2026-04-30T00:00:00.000+00:00\"," +
                "\"conflicts\":[{\"resourceType\":\"PAGE_SCHEMA\",\"reason\":\"forced\"}]}");
        promotionMapper.updateById(forced);

        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "ship"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts");
    }

    @Test
    void apply_alreadyApplied_isImmutable() {
        Long sourceEnv = createEnv("apply_imm_src_" + shortId());
        Long targetEnv = createEnv("apply_imm_tgt_" + shortId());
        PageSchema sourcePage = insertPage(sourceEnv, "p_im_" + shortId(), "[{\"a\":1}]");
        PromotionResponse draft = promotionService.create(
                buildRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());
        promotionService.apply(draft.getPid(), testTenant.getId(), testUser.getId(), "first");

        // Second apply call — state machine blocks APPLIED → APPLIED
        assertThatThrownBy(() -> promotionService.apply(
                draft.getPid(), testTenant.getId(), testUser.getId(), "again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPLIED");
    }

    // ---- helpers ----

    private Long createEnv(String code) {
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode(code);
        req.setName(code);
        req.setIsDefault(false);
        req.setSortOrder(0);
        var env = environmentService.create(req, testTenant.getId(), testUser.getId());
        Environment probe = environmentMapper.selectOne(
                new QueryWrapper<Environment>().eq("pid", env.getPid()).eq("tenant_id", testTenant.getId()));
        return probe.getId();
    }

    private String envPidById(Long envId) {
        return environmentMapper.selectById(envId).getPid();
    }

    private PageSchema insertPage(Long envId, String pageKey, String blocksJson) {
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
