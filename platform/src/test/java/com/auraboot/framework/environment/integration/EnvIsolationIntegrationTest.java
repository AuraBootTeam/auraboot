package com.auraboot.framework.environment.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.dto.EnvironmentResponse;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for cross-environment isolation: pages saved in dev env are invisible from
 * staging env queries, and vice-versa. Bypass closure restores cross-env visibility for legitimate
 * promotion reads.
 *
 * <p>Verifies the contract: <b>WHERE env_id = ?</b> auto-injection by the @EnvScoped inner
 * interceptor + auto-fill of env_id on INSERT by EnvIdMetaObjectHandler.
 */
class EnvIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @AfterEach
    void clearEnvContext() {
        // BaseIntegrationTest.clearTenantContext clears the entire MetaContext, but be explicit
        // about env state in case test order changes.
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void pageInDev_invisibleInStaging() {
        Long devEnvId = createEnv("dev_" + shortId());
        Long stagingEnvId = createEnv("staging_" + shortId());

        // Insert page while MetaContext points at dev → MetaObjectHandler stamps env_id=dev
        MetaContext.setEnvironmentId(devEnvId);
        PageSchema devPage = newBarePage("page_" + shortId());
        pageSchemaMapper.insert(devPage);
        assertThat(devPage.getEnvId()).isEqualTo(devEnvId);

        // Switch to staging → query should NOT see the dev page
        MetaContext.setEnvironmentId(stagingEnvId);
        PageSchema fromStaging = pageSchemaMapper.selectByPid(devPage.getPid());
        assertThat(fromStaging).isNull();

        // Switch back to dev → page is visible
        MetaContext.setEnvironmentId(devEnvId);
        PageSchema fromDev = pageSchemaMapper.selectByPid(devPage.getPid());
        assertThat(fromDev).isNotNull();
        assertThat(fromDev.getPid()).isEqualTo(devPage.getPid());
    }

    @Test
    void bypassEnvFilter_returnsCrossEnvResults() {
        Long devEnvId = createEnv("dev2_" + shortId());
        Long stagingEnvId = createEnv("staging2_" + shortId());

        MetaContext.setEnvironmentId(devEnvId);
        PageSchema devPage = newBarePage("p_" + shortId());
        pageSchemaMapper.insert(devPage);

        MetaContext.setEnvironmentId(stagingEnvId);
        PageSchema stagingPage = newBarePage("p_" + shortId());
        pageSchemaMapper.insert(stagingPage);

        // Default: staging context sees only staging
        assertThat(pageSchemaMapper.selectByPid(devPage.getPid())).isNull();

        // bypass: should see both pages regardless of current env
        Integer crossCount = MetaContext.runWithoutEnvFilter(() -> {
            QueryWrapper<PageSchema> qw = new QueryWrapper<>();
            qw.in("pid", devPage.getPid(), stagingPage.getPid());
            return pageSchemaMapper.selectList(qw).size();
        });
        assertThat(crossCount).isEqualTo(2);

        // After bypass exits, filter is restored
        assertThat(pageSchemaMapper.selectByPid(devPage.getPid())).isNull();
    }

    @Test
    void noEnvContext_skipsFilter() {
        Long devEnvId = createEnv("dev3_" + shortId());

        MetaContext.setEnvironmentId(devEnvId);
        PageSchema page = newBarePage("p_" + shortId());
        pageSchemaMapper.insert(page);

        // Clear env context entirely → interceptor short-circuits, page is visible
        MetaContext.setEnvironmentId(null);
        PageSchema fetched = pageSchemaMapper.selectByPid(page.getPid());
        assertThat(fetched).isNotNull();
        assertThat(fetched.getEnvId()).isEqualTo(devEnvId);
    }

    @Test
    void metaObjectHandler_fallsBackToTenantDefaultWhenContextMissing() {
        // Pre-create a default env so findOrCreateDefaultId returns existing rather than throw
        // on duplicate (we always run the same testTenant across cases).
        Long defaultId = environmentService.findOrCreateDefaultId(testTenant.getId());

        // No env context at insert time → MetaObjectHandler should stamp the tenant default
        MetaContext.setEnvironmentId(null);
        PageSchema page = newBarePage("p_" + shortId());
        pageSchemaMapper.insert(page);

        assertThat(page.getEnvId()).isEqualTo(defaultId);
    }

    @Test
    void explicitEnvIdOnEntity_isHonored() {
        Long devEnvId = createEnv("dev4_" + shortId());
        Long otherEnvId = createEnv("other_" + shortId());

        MetaContext.setEnvironmentId(otherEnvId);
        PageSchema page = newBarePage("p_" + shortId());
        page.setEnvId(devEnvId);  // explicit set wins (used by promotion cross-env writes)
        pageSchemaMapper.insert(page);

        assertThat(page.getEnvId()).isEqualTo(devEnvId);

        // The page is visible in dev, not in other
        MetaContext.setEnvironmentId(devEnvId);
        assertThat(pageSchemaMapper.selectByPid(page.getPid())).isNotNull();
        MetaContext.setEnvironmentId(otherEnvId);
        assertThat(pageSchemaMapper.selectByPid(page.getPid())).isNull();
    }

    @Autowired
    private com.auraboot.framework.environment.dao.mapper.EnvironmentMapper environmentMapper;

    private Long createEnv(String code) {
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode(code);
        req.setName(code);
        req.setIsDefault(false);
        req.setSortOrder(0);
        EnvironmentResponse env = environmentService.create(req, testTenant.getId(), testUser.getId());
        com.auraboot.framework.environment.dao.entity.Environment probe = environmentMapper.selectOne(
                new QueryWrapper<com.auraboot.framework.environment.dao.entity.Environment>()
                        .eq("pid", env.getPid())
                        .eq("tenant_id", testTenant.getId()));
        return probe.getId();
    }

    private PageSchema newBarePage(String pageKey) {
        // Use full ULID for both pid and name to avoid (tenant_id, name, version) unique-index
        // collisions on fast back-to-back inserts within the same millisecond.
        String unique = UniqueIdGenerator.generate();
        PageSchema p = new PageSchema();
        p.setPid(unique);
        p.setTenantId(testTenant.getId());
        p.setPageKey(pageKey);
        p.setModelCode("test_model");  // must be NOT NULL on ab_page_schema
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

    private static String shortId() {
        return UniqueIdGenerator.generate().toLowerCase();
    }
}
