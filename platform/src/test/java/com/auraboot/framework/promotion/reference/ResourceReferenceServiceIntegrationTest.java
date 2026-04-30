package com.auraboot.framework.promotion.reference;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.promotion.reference.dao.entity.ResourceReference;
import com.auraboot.framework.promotion.reference.service.ResourceReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the reverse-reference index — proves the UX-contract real-use criterion:
 * "delete a field, Diff Viewer can name every page that referenced it."
 */
class ResourceReferenceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ResourceReferenceService referenceService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void refresh_extractsModelAndFieldReferences() {
        Long envId = createEnv("ref_" + shortId());
        MetaContext.setEnvironmentId(envId);

        PageSchema page = newPage(envId, "p_" + shortId(), "tcrm_lead",
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"name\"},{\"code\":\"status\"}]}]");

        referenceService.refresh(page);

        // Direct mapper read (not via the service which scopes by env) to verify all rows
        QueryWrapper<ResourceReference> qw = new QueryWrapper<>();
        qw.eq("source_id", page.getPid()).eq("deleted_flag", false);
        List<ResourceReference> all = referenceMapper.selectList(qw);
        assertThat(all).hasSize(3);  // 1 MODEL + 2 FIELD
        assertThat(all).extracting(ResourceReference::getTargetType, ResourceReference::getTargetCode)
                .containsExactlyInAnyOrder(
                        tuple("MODEL", "tcrm_lead"),
                        tuple("FIELD", "name"),
                        tuple("FIELD", "status")
                );
    }

    @Test
    void findReferencingPages_returnsAllPagesReferencingField() {
        Long envId = createEnv("imp_" + shortId());
        MetaContext.setEnvironmentId(envId);

        // Two pages both reference field "ap_wp_name"
        PageSchema pageA = newPage(envId, "p_a_" + shortId(), "ap_work_package",
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"ap_wp_name\"}]}]");
        PageSchema pageB = newPage(envId, "p_b_" + shortId(), "ap_work_package",
                "[{\"blockType\":\"table\",\"columns\":[{\"code\":\"ap_wp_name\"},{\"code\":\"created_at\"}]}]");
        // Page C in same env does NOT reference ap_wp_name
        PageSchema pageC = newPage(envId, "p_c_" + shortId(), "tcrm_lead",
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"name\"}]}]");

        referenceService.refresh(pageA);
        referenceService.refresh(pageB);
        referenceService.refresh(pageC);

        List<ResourceReference> hits = referenceService.findReferencingPages("FIELD", "ap_wp_name");
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(ResourceReference::getSourceId)
                .containsExactlyInAnyOrder(pageA.getPid(), pageB.getPid());
    }

    @Test
    void refresh_replacesPriorReferences() {
        Long envId = createEnv("repl_" + shortId());
        MetaContext.setEnvironmentId(envId);

        PageSchema page = newPage(envId, "p_" + shortId(), "tcrm_lead",
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"old_field\"}]}]");
        referenceService.refresh(page);
        assertThat(referenceService.findReferencingPages("FIELD", "old_field")).hasSize(1);

        // Update page content — old_field gone, new_field added
        page.setBlocks("[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"new_field\"}]}]");
        referenceService.refresh(page);

        assertThat(referenceService.findReferencingPages("FIELD", "old_field")).isEmpty();
        assertThat(referenceService.findReferencingPages("FIELD", "new_field")).hasSize(1);
    }

    @Test
    void findReferencingPages_isolatedByEnv() {
        Long devEnv = createEnv("dev_iso_" + shortId());
        Long prodEnv = createEnv("prod_iso_" + shortId());

        // Insert page A in dev and refresh refs
        MetaContext.setEnvironmentId(devEnv);
        PageSchema devPage = newPage(devEnv, "p_dev_" + shortId(), "tcrm_lead",
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"shared_field\"}]}]");
        referenceService.refresh(devPage);

        // Insert page B in prod, also references shared_field
        MetaContext.setEnvironmentId(prodEnv);
        PageSchema prodPage = newPage(prodEnv, "p_prod_" + shortId(), "tcrm_lead",
                "[{\"blockType\":\"filters\",\"fields\":[{\"code\":\"shared_field\"}]}]");
        referenceService.refresh(prodPage);

        // Query from dev → only sees dev's reference
        MetaContext.setEnvironmentId(devEnv);
        List<ResourceReference> devHits = referenceService.findReferencingPages("FIELD", "shared_field");
        assertThat(devHits).hasSize(1);
        assertThat(devHits.get(0).getSourceId()).isEqualTo(devPage.getPid());

        // Query from prod → only sees prod's reference
        MetaContext.setEnvironmentId(prodEnv);
        List<ResourceReference> prodHits = referenceService.findReferencingPages("FIELD", "shared_field");
        assertThat(prodHits).hasSize(1);
        assertThat(prodHits.get(0).getSourceId()).isEqualTo(prodPage.getPid());
    }

    @Autowired
    private com.auraboot.framework.promotion.reference.dao.mapper.ResourceReferenceMapper referenceMapper;

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

    private PageSchema newPage(Long envId, String pageKey, String modelCode, String blocksJson) {
        String unique = UniqueIdGenerator.generate();
        PageSchema p = new PageSchema();
        p.setPid(unique);
        p.setTenantId(testTenant.getId());
        p.setEnvId(envId);
        p.setPageKey(pageKey);
        p.setModelCode(modelCode);
        p.setName("test_" + unique);
        p.setKind("list");
        p.setProfile("admin");
        p.setSchemaVersion(2);
        p.setBlocks(blocksJson);
        p.setStatus("draft");
        p.setVersion(1);
        p.setIsCurrent(true);
        p.setRowVersion(1);
        return p;
    }

    private static String shortId() {
        return UniqueIdGenerator.generate().toLowerCase();
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
