package com.auraboot.framework.environment.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
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
 * Integration test for env-layering #17 lock guard. Enforces "locked env rejects direct
 * writes; only promotion (with four-eyes) can write to a locked env" UX contract.
 *
 * <p>PoC scope: INSERT only via AuraBootObjectHandler.insertFill. UPDATE/DELETE coverage
 * deferred to a follow-up MyBatis-Plus inner interceptor.
 */
class EnvLockGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Autowired
    private PromotionService promotionService;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void directInsert_intoLockedEnv_isRejected() {
        Long envId = createEnv("locked_" + shortId());
        environmentService.lock(envPidById(envId), testTenant.getId(), testUser.getId(), "freeze");

        MetaContext.setEnvironmentId(envId);
        PageSchema page = newBarePage("p_" + shortId());

        // MyBatis wraps the lock-guard IllegalStateException as a MyBatisSystemException; assert
        // on the root cause to pin the actual rejection reason.
        assertThatThrownBy(() -> pageSchemaMapper.insert(page))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked")
                .hasMessageContaining("/api/promotions");
    }

    @Test
    void directInsert_intoUnlockedEnv_isAllowed() {
        Long envId = createEnv("unlocked_" + shortId());

        MetaContext.setEnvironmentId(envId);
        PageSchema page = newBarePage("p_" + shortId());
        pageSchemaMapper.insert(page);

        assertThat(page.getEnvId()).isEqualTo(envId);
    }

    @Test
    void runWithoutLockGuard_allowsInsertIntoLockedEnv() {
        Long envId = createEnv("bypass_" + shortId());
        environmentService.lock(envPidById(envId), testTenant.getId(), testUser.getId(), "freeze");

        MetaContext.setEnvironmentId(envId);
        PageSchema page = newBarePage("p_" + shortId());

        // Inside the bypass closure, the guard is silenced
        MetaContext.runWithoutLockGuard(() -> {
            pageSchemaMapper.insert(page);
        });

        assertThat(page.getEnvId()).isEqualTo(envId);

        // After the closure exits, guard is re-armed — second insert blocked
        PageSchema page2 = newBarePage("p_" + shortId());
        assertThatThrownBy(() -> pageSchemaMapper.insert(page2))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void directUpdate_onPageInLockedEnv_isRejected() {
        // env-layering #19 — UPDATE on @EnvScoped table while in a locked env throws.
        Long envId = createEnv("up_lock_" + shortId());

        // Insert a page first (env still unlocked)
        MetaContext.setEnvironmentId(envId);
        PageSchema page = newBarePage("p_up_" + shortId());
        pageSchemaMapper.insert(page);

        // Now lock the env
        environmentService.lock(envPidById(envId), testTenant.getId(), testUser.getId(), "freeze");

        // Attempt to update via the standard MP path — guard fires
        page.setName("renamed_after_lock");
        assertThatThrownBy(() -> pageSchemaMapper.updateById(page))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked")
                .hasMessageContaining("/api/promotions");
    }

    @Test
    void directDelete_onPageInLockedEnv_isRejected() {
        Long envId = createEnv("del_lock_" + shortId());

        MetaContext.setEnvironmentId(envId);
        PageSchema page = newBarePage("p_del_" + shortId());
        pageSchemaMapper.insert(page);

        environmentService.lock(envPidById(envId), testTenant.getId(), testUser.getId(), "freeze");

        // Hard delete via deleteById — guard fires (DELETE on env-scoped table while env locked)
        Long pageId = page.getId();
        assertThatThrownBy(() -> pageSchemaMapper.deleteById(pageId))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void runWithoutLockGuard_allowsUpdateOnLockedEnv() {
        // Promotion / system migration path — bypass closure must let UPDATE through.
        Long envId = createEnv("up_bypass_" + shortId());

        MetaContext.setEnvironmentId(envId);
        PageSchema page = newBarePage("p_ub_" + shortId());
        pageSchemaMapper.insert(page);

        environmentService.lock(envPidById(envId), testTenant.getId(), testUser.getId(), "freeze");

        page.setName("renamed_via_bypass");
        MetaContext.runWithoutLockGuard(() -> pageSchemaMapper.updateById(page));

        // Sanity: re-read to confirm update went through
        PageSchema reloaded = pageSchemaMapper.selectByPid(page.getPid());
        assertThat(reloaded.getName()).isEqualTo("renamed_via_bypass");
    }

    @Test
    void matchesTable_handlesSqlVariations() {
        // env-layering #5 follow-up — pure-logic edge case coverage for the whole-word table
        // matcher inside EnvWriteLockGuardInnerInterceptor. Run inside this class so the
        // already-loaded Spring context is amortized across cases (per-class Spring restart
        // makes a standalone unit test class take >60 min in this project).
        var matcher = com.auraboot.framework.application.database.mybatis.EnvWriteLockGuardInnerInterceptor.class;
        java.lang.reflect.Method m;
        try {
            m = matcher.getDeclaredMethod("matchesTable", String.class, String.class);
            m.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("matchesTable signature changed", e);
        }
        java.util.function.BiFunction<String, String, Boolean> match = (sql, table) -> {
            try { return (Boolean) m.invoke(null, sql.toLowerCase(), table); }
            catch (Exception e) { throw new AssertionError(e); }
        };

        // Positive cases
        assertThat(match.apply("delete from ab_page_schema where pid = ?", "ab_page_schema")).isTrue();
        assertThat(match.apply("update ab_page_schema set name=?", "ab_page_schema")).isTrue();
        assertThat(match.apply("delete from ab_page_schema_history where op_at < ?", "ab_page_schema_history")).isTrue();
        assertThat(match.apply("update ab_page_schema p set name=? from ab_environment e where p.env_id=e.id", "ab_page_schema")).isTrue();
        // Subquery: both tables present
        assertThat(match.apply("delete from ab_page_schema where pid in (select pid from ab_page_schema_history)", "ab_page_schema")).isTrue();
        assertThat(match.apply("delete from ab_page_schema where pid in (select pid from ab_page_schema_history)", "ab_page_schema_history")).isTrue();
        // Batch IN
        assertThat(match.apply("delete from ab_page_schema where pid in (?,?,?,?)", "ab_page_schema")).isTrue();
        // Newline / tab boundaries
        assertThat(match.apply("delete from ab_page_schema\n where deleted_flag=true", "ab_page_schema")).isTrue();
        assertThat(match.apply("delete\tfrom\tab_page_schema\twhere id=?", "ab_page_schema")).isTrue();
        // Comma separator
        assertThat(match.apply("update ab_page_schema, ab_environment set ...", "ab_page_schema")).isTrue();
        // Start-of-string + end-of-string boundaries
        assertThat(match.apply("ab_page_schema", "ab_page_schema")).isTrue();
        assertThat(match.apply("delete from ab_page_schema", "ab_page_schema")).isTrue();

        // Negative cases — prefix / substring traps
        assertThat(match.apply("delete from ab_page_schema_history where op_at < ?", "ab_page_schema")).isFalse();
        assertThat(match.apply("delete from ab_page_schema_archive where ...", "ab_page_schema")).isFalse();
        assertThat(match.apply("delete from foo_ab_page_schema where ...", "ab_page_schema")).isFalse();
        assertThat(match.apply("delete from xab_page_schema where ...", "ab_page_schema")).isFalse();
        assertThat(match.apply("select 1", "ab_page_schema")).isFalse();
        // Empty inputs are safe
        assertThat(match.apply("", "ab_page_schema")).isFalse();
        assertThat(match.apply("delete from ab_page_schema", "")).isFalse();
    }

    @Test
    void promotionApply_toLockedTarget_succeedsViaFourEyesBypass() {
        Long sourceEnv = createEnv("src_lk_" + shortId());
        Long targetEnv = createEnv("tgt_lk_" + shortId());
        // Lock the target — direct writes would be rejected, but apply must succeed
        environmentService.lock(envPidById(targetEnv), testTenant.getId(), testUser.getId(), "regulatory freeze");

        // Insert a source page (source env is unlocked)
        MetaContext.setEnvironmentId(sourceEnv);
        PageSchema sourcePage = newBarePage("p_promote_" + shortId());
        pageSchemaMapper.insert(sourcePage);
        MetaContext.setEnvironmentId(null);

        // Create + validate promotion (different approver to satisfy four-eyes)
        PromotionResponse draft = promotionService.create(
                buildPromotionRequest(sourceEnv, targetEnv, sourcePage.getPid()),
                testTenant.getId(), testUser.getId());
        promotionService.validate(draft.getPid(), testTenant.getId());

        Long otherApprover = testUser.getId() + 99;
        PromotionResponse applied = promotionService.apply(
                draft.getPid(), testTenant.getId(), otherApprover, "ship to locked prod");

        assertThat(applied.getStatus()).isEqualTo("APPLIED");

        // Target env now has the page despite being locked (apply used the bypass)
        MetaContext.setEnvironmentId(targetEnv);
        PageSchema targetPage = pageSchemaMapper.selectAnyByPageKey(sourcePage.getPageKey());
        assertThat(targetPage).isNotNull();
        assertThat(targetPage.getEnvId()).isEqualTo(targetEnv);
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

    private PageSchema newBarePage(String pageKey) {
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
        p.setBlocks("[]");
        p.setStatus("draft");
        p.setVersion(1);
        p.setIsCurrent(true);
        p.setRowVersion(1);
        return p;
    }

    private PromotionRequest buildPromotionRequest(Long sourceEnv, Long targetEnv, String pagePid) {
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
