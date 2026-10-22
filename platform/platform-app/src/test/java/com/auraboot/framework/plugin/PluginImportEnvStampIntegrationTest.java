package com.auraboot.framework.plugin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
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
 * Regression for code-review finding (#16): native {@code insertForPluginImport} SQL bypasses
 * the MetaObjectHandler auto-fill. Without an explicit envId param plus caller-side resolution,
 * plugin-imported pages would land with {@code env_id = NULL} and become invisible to all
 * env-scoped reads. This test pins down the contract.
 */
class PluginImportEnvStampIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void insertForPluginImport_stampsEnvIdAndPageIsVisibleInThatEnv() {
        Long defaultEnvId = environmentService.findOrCreateDefaultId(testTenant.getId());
        String pid = UniqueIdGenerator.generate();
        String pageKey = "plugin_import_" + shortId();

        pageSchemaMapper.insertForPluginImport(
                pid, testTenant.getId(), defaultEnvId, "draft",
                pageKey, "test_model",
                "Test", "{}", null, "list", "admin",
                "{}", "[]", 2,
                false, null, null, 0, "{}", "test_plugin");

        // Verify env_id was stamped on the row (read with no env context to bypass filter)
        MetaContext.setEnvironmentId(null);
        PageSchema noFilter = pageSchemaMapper.selectByPid(pid);
        assertThat(noFilter).isNotNull();
        assertThat(noFilter.getEnvId()).isEqualTo(defaultEnvId);
    }

    @Test
    void pluginImportedPage_visibleInDefaultEnv_invisibleFromOtherEnv() {
        Long defaultEnvId = environmentService.findOrCreateDefaultId(testTenant.getId());
        Long otherEnvId = createEnv("other_" + shortId());

        String pid = UniqueIdGenerator.generate();
        String pageKey = "plugin_iso_" + shortId();
        pageSchemaMapper.insertForPluginImport(
                pid, testTenant.getId(), defaultEnvId, "draft",
                pageKey, "test_model",
                "Test", "{}", null, "list", "admin",
                "{}", "[]", 2,
                false, null, null, 0, "{}", "test_plugin");

        // From the imported env → visible
        MetaContext.setEnvironmentId(defaultEnvId);
        PageSchema visible = pageSchemaMapper.selectByPid(pid);
        assertThat(visible).isNotNull();
        assertThat(visible.getEnvId()).isEqualTo(defaultEnvId);

        // From a different env → invisible (this is the bug-fix evidence)
        MetaContext.setEnvironmentId(otherEnvId);
        PageSchema invisible = pageSchemaMapper.selectByPid(pid);
        assertThat(invisible).isNull();
    }

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

    private static String shortId() {
        return UniqueIdGenerator.generate().toLowerCase();
    }
}
