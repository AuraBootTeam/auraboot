package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-PostgreSQL ownership proof for inherited PageSchema authoring. */
class AuthoringOwnershipOverrideIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringWorkspaceService workspaceService;
    @Autowired
    private AuthoringGovernanceService governanceService;
    @Autowired
    private AuthoringCapabilityRegistry capabilityRegistry;
    @Autowired
    private PageSchemaMapper pageSchemaMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void inheritedApplicationPagePublishesTenantOverrideWithoutMutatingSource() throws Exception {
        PageSchema source = insertPage("APPLICATION", "plugin-orders");
        String sourceBlocks = source.getBlocks();
        int sourceRowVersion = source.getRowVersion();

        SessionView opened = workspaceService.open(new OpenSessionRequest(source.getPid(), null));
        SessionView second = workspaceService.open(new OpenSessionRequest(source.getPid(), null));

        assertThat(opened.ownership().ownershipScope()).isEqualTo("TENANT");
        assertThat(opened.ownership().sourceOwnershipScope()).isEqualTo("APPLICATION");
        assertThat(opened.ownership().sourcePagePid()).isEqualTo(source.getPid());
        assertThat(opened.ownership().tenantOverride()).isTrue();
        assertThat(opened.ownership().sourceMutable()).isFalse();
        assertThat(opened.ownership().origin()).isEqualTo("TENANT_OVERRIDE");
        assertThat(opened.ownership().overridePid()).isNotBlank();
        assertThat(second.ownership().overridePid()).isEqualTo(opened.ownership().overridePid());
        assertThat(overrideCount(source.getPid())).isEqualTo(1);
        assertThat(auditCount("TENANT_OVERRIDE_CREATED", source.getPid())).isEqualTo(1);
        assertThat(auditCount("TENANT_OVERRIDE_REUSED", source.getPid())).isEqualTo(1);

        SessionView changed = workspaceService.applyStudio(
                opened.sessionPid(),
                new ApplyPatchRequest(
                        opened.revision(),
                        "table-1",
                        "/dataSource",
                        PatchOperation.ADD,
                        objectMapper.valueToTree(Map.of("model", "payments")),
                        tableManifest())).session();

        Map<String, Object> itemLineage = jdbcTemplate.queryForMap("""
                SELECT ownership_scope, source_resource_pid, override_pid
                FROM ab_authoring_change_item
                WHERE tenant_id = ? AND env_id = ? AND change_set_id = (
                    SELECT id FROM ab_authoring_change_set
                    WHERE tenant_id = ? AND env_id = ? AND pid = ?)
                """,
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(),
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(), opened.changeSetPid());
        assertThat(itemLineage)
                .containsEntry("ownership_scope", "TENANT")
                .containsEntry("source_resource_pid", source.getPid())
                .containsEntry("override_pid", opened.ownership().overridePid());

        RevisionRequest revision = new RevisionRequest(changed.revision());
        SessionView prepared = governanceService.prepare(opened.sessionPid(), revision);
        assertThat(prepared.validationState()).isEqualTo("VALID");
        assertThat(prepared.impactState()).isEqualTo("KNOWN");
        ChangeSetView submitted = governanceService.submit(opened.sessionPid(), revision);
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");

        long environmentId = MetaContext.getCurrentEnvironmentId();
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 100_000,
                    "ownership-reviewer", "ownership-reviewer");
            MetaContext.setEnvironmentId(environmentId);
            governanceService.approve(
                    opened.changeSetPid(), new ReviewRequest(changed.revision(), "ownership checked"));
        } finally {
            applyTestMetaContext();
        }

        ReleaseView release = governanceService.publish(opened.changeSetPid(), revision);
        Map<String, Object> releaseLineage = jdbcTemplate.queryForMap("""
                SELECT item.ownership_scope, item.source_resource_pid, item.override_pid,
                       release.manifest #>> '{resources,0,ownershipScope}'
                           AS manifest_ownership_scope,
                       release.manifest #>> '{resources,0,sourceOwnershipScope}'
                           AS manifest_source_ownership_scope,
                       release.manifest #>> '{resources,0,overridePid}' AS manifest_override_pid
                FROM ab_authoring_release_item item
                JOIN ab_authoring_release release ON release.id = item.release_id
                WHERE release.pid = ?
                """, release.releasePid());
        assertThat(releaseLineage)
                .containsEntry("ownership_scope", "TENANT")
                .containsEntry("source_resource_pid", source.getPid())
                .containsEntry("override_pid", opened.ownership().overridePid())
                .containsEntry("manifest_ownership_scope", "TENANT")
                .containsEntry("manifest_source_ownership_scope", "APPLICATION")
                .containsEntry("manifest_override_pid", opened.ownership().overridePid());

        PageSchema unchanged = pageSchemaMapper.selectByPid(source.getPid());
        assertThat(objectMapper.readTree(unchanged.getBlocks()))
                .isEqualTo(objectMapper.readTree(sourceBlocks));
        assertThat(unchanged.getRowVersion()).isEqualTo(sourceRowVersion);
        assertThat(unchanged.getOwnershipScope()).isEqualTo("APPLICATION");
        assertThat(changed.snapshot().at("/blocks/0/dataSource/model").asText())
                .isEqualTo("payments");
    }

    @Test
    void tenantOwnedPageDoesNotCreateAnOverride() {
        PageSchema source = insertPage("TENANT", null);

        SessionView opened = workspaceService.open(new OpenSessionRequest(source.getPid(), null));

        assertThat(opened.ownership().ownershipScope()).isEqualTo("TENANT");
        assertThat(opened.ownership().sourceOwnershipScope()).isEqualTo("TENANT");
        assertThat(opened.ownership().tenantOverride()).isFalse();
        assertThat(opened.ownership().sourceMutable()).isTrue();
        assertThat(opened.ownership().overridePid()).isNull();
        assertThat(opened.ownership().origin()).isEqualTo("DESIGN_STUDIO");
        assertThat(overrideCount(source.getPid())).isZero();
    }

    private PageSchema insertPage(String ownershipScope, String pluginPid) {
        ensureModel("test_model");
        ensureModel("payments");
        String pid = UniqueIdGenerator.generate();
        PageSchema page = new PageSchema();
        page.setPid(pid);
        page.setTenantId(testTenant.getId());
        page.setEnvId(MetaContext.getCurrentEnvironmentId());
        page.setPageKey("ownership_" + pid.toLowerCase());
        page.setModelCode("test_model");
        page.setName("Ownership " + pid);
        page.setKind("list");
        page.setSchemaVersion(4);
        page.setProfile("admin");
        page.setTitle("{\"en-US\":\"Orders\"}");
        page.setLayout("{\"type\":\"stack\"}");
        page.setBlocks("[{\"id\":\"table-1\",\"blockType\":\"table\","
                + "\"props\":{\"density\":\"normal\"}}]");
        page.setStatus("published");
        page.setVersion(1);
        page.setSemver("1.0.0");
        page.setRowVersion(1);
        page.setIsCurrent(true);
        page.setIsTemplate(false);
        page.setPluginPid(pluginPid);
        if (pluginPid == null) {
            page.setOwnershipScope(ownershipScope);
        }
        page.setOwnershipRef(pluginPid);
        page.setDeletedFlag(false);
        pageSchemaMapper.insert(page);
        return page;
    }

    private void ensureModel(String modelCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_meta_model
                WHERE tenant_id = ? AND code = ? AND is_current = TRUE
                  AND deleted_flag = FALSE
                """, Integer.class, testTenant.getId(), modelCode);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, version, is_current,
                    row_version, status, deleted_flag)
                VALUES (?, ?, ?, ?, 1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), testTenant.getId(), modelCode,
                "mt_" + modelCode);
    }

    private String tableManifest() {
        return capabilityRegistry.find("table").orElseThrow().checksum();
    }

    private int overrideCount(String sourcePagePid) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_tenant_override
                WHERE tenant_id = ? AND env_id = ? AND source_resource_pid = ?
                """, Integer.class,
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(), sourcePagePid);
        return count == null ? 0 : count;
    }

    private int auditCount(String eventType, String sourcePagePid) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND env_id = ? AND event_type = ?
                  AND resource_pid = ?
                """, Integer.class,
                testTenant.getId(), MetaContext.getCurrentEnvironmentId(), eventType, sourcePagePid);
        return count == null ? 0 : count;
    }
}
