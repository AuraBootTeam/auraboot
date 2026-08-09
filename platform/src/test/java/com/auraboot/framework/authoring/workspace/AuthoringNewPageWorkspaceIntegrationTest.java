package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.database.snowflake.SnowflakeIdGeneratorConfig;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateNewPageWorkspaceRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.PageSchemaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringNewPageWorkspaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringWorkspaceService workspaceService;

    @Autowired
    private AuthoringGovernanceService governanceService;

    @Autowired
    private AuthoringActiveReleaseResolver activeReleaseResolver;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SnowflakeIdGeneratorConfig idGenerator;

    @Test
    void newPageAndMenuStayInvisibleUntilReviewedPublishThenMaterializeAtomically() {
        Fixture fixture = fixture();
        assertThat(workspaceService.newPageOptions().models())
                .anyMatch(option -> option.value().equals(fixture.modelCode()));
        SessionView created = workspaceService.createNewPageWorkspace(
                fixture.source().sessionPid(), fixture.request());

        assertThat(created.revision()).isEqualTo(3);
        assertThat(created.riskLevel()).isEqualTo("L3");
        assertThat(created.route()).isEqualTo("HANDOFF_STUDIO");
        assertThat(created.publishPolicy()).isEqualTo("STUDIO_APPROVAL");
        assertThat(created.snapshot().path("_authoringResource").path("lifecycle").asText())
                .isEqualTo("NEW");
        assertThat(created.snapshot().path("modelCode").asText()).isEqualTo(fixture.modelCode());
        assertThat(created.snapshot().at("/blocks/0/blockType").asText()).isEqualTo("list");
        assertThat(created.snapshot().at("/blocks/0/dataSource/model").asText())
                .isEqualTo(fixture.modelCode());
        assertThat(created.snapshot().at("/blocks/0/blocks")).isEmpty();
        assertThat(countPage(fixture.pageKey())).isZero();
        assertThat(countMenu(fixture.menuCode())).isZero();
        assertThat(changeItemPaths(created.changeSetPid()))
                .containsExactly("/$resource/page", "/$resource/menu");

        governanceService.prepare(created.sessionPid(), new RevisionRequest(3));
        governanceService.submit(created.sessionPid(), new RevisionRequest(3));
        approveAsDifferentActor(created);
        governanceService.publish(created.changeSetPid(), new RevisionRequest(3));

        assertThat(countPage(fixture.pageKey())).isOne();
        assertThat(countMenu(fixture.menuCode())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT page_pid FROM ab_menu
                WHERE tenant_id = ? AND code = ? AND deleted_flag = FALSE
                """, String.class, getTestTenant().getId(), fixture.menuCode()))
                .isEqualTo(created.pagePid());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT extension -> 'authoringEnvironmentIds' @> jsonb_build_array(CAST(? AS BIGINT))
                FROM ab_menu WHERE tenant_id = ? AND code = ? AND deleted_flag = FALSE
                """, Boolean.class, MetaContext.getCurrentEnvironmentId(),
                getTestTenant().getId(), fixture.menuCode())).isTrue();
        PageSchemaDTO runtime = pageSchemaService.findRuntimeByPid(created.pagePid());
        assertThat(runtime.getStatus()).isEqualTo("published");
        assertThat(runtime.getPageKey()).isEqualTo(fixture.pageKey());
        assertThat(runtime.getModelCode()).isEqualTo(fixture.modelCode());
        assertThat(runtime.getRuntime().source()).isEqualTo("AUTHORING_RELEASE");
        AuthoringActiveReleaseResolver.ActiveRelease active =
                activeReleaseResolver.findByResource(
                        getTestTenant().getId(), MetaContext.getCurrentEnvironmentId(),
                        "PAGE_SCHEMA", created.pagePid());
        assertThat(active.snapshot().has("_authoringResource")).isFalse();
    }

    @Test
    void conflictingMenuCreatedAfterReviewRollsBackPageAndReleaseMaterialization() {
        Fixture fixture = fixture();
        SessionView created = workspaceService.createNewPageWorkspace(
                fixture.source().sessionPid(), fixture.request());
        governanceService.prepare(created.sessionPid(), new RevisionRequest(3));
        governanceService.submit(created.sessionPid(), new RevisionRequest(3));
        approveAsDifferentActor(created);
        insertLeafMenu(fixture.menuCode(), fixture.menuPath(), fixture.parentMenuId(), null);

        assertThatThrownBy(() -> governanceService.publish(
                created.changeSetPid(), new RevisionRequest(3)))
                .isInstanceOf(AuthoringStaleStateException.class)
                .hasMessageContaining("authoring.new-page.menu-conflict");

        assertThat(countPage(fixture.pageKey())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_release release
                JOIN ab_authoring_change_set change_set ON change_set.id = release.change_set_id
                WHERE change_set.pid = ?
                """, Integer.class, created.changeSetPid())).isZero();
    }

    @Test
    void dashboardPageSchemaCreationFailsClosedBeforeAnyResourceIsReserved() {
        Fixture fixture = fixture();
        CreateNewPageWorkspaceRequest invalid = requestWithKind(fixture.request(), "dashboard");

        assertThatThrownBy(() -> workspaceService.createNewPageWorkspace(
                fixture.source().sessionPid(), invalid))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("authoring.new-page.kind-unsupported");

        assertThat(countPage(fixture.pageKey())).isZero();
        assertThat(countMenu(fixture.menuCode())).isZero();
    }

    @Test
    void unpublishedOrMissingModelFailsClosedBeforeAnyResourceIsReserved() {
        Fixture fixture = fixture();
        CreateNewPageWorkspaceRequest invalid = requestWithModel(
                fixture.request(), "missing_model");

        assertThatThrownBy(() -> workspaceService.createNewPageWorkspace(
                fixture.source().sessionPid(), invalid))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("authoring.new-page.model-invalid");

        assertThat(countPage(fixture.pageKey())).isZero();
        assertThat(countMenu(fixture.menuCode())).isZero();
    }

    private Fixture fixture() {
        PageSchema sourcePage = insertSourcePage();
        SessionView source = workspaceService.open(new OpenSessionRequest(sourcePage.getPid(), null));
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String parentCode = "authoring_dir_" + suffix;
        long parentId = insertParentMenu(parentCode);
        String permissionCode = "page.authoring_" + suffix + ".read";
        insertPermission(permissionCode);
        String modelCode = "authoring_model_" + suffix;
        insertModel(modelCode);
        String pageKey = "authoring_new_" + suffix;
        String menuCode = "authoring.menu." + suffix;
        String menuPath = "/authoring/new/" + suffix;
        CreateNewPageWorkspaceRequest request = new CreateNewPageWorkspaceRequest(
                1, pageKey, "Authoring new " + suffix, "受治理新页面", "integration",
                "list", modelCode, parentCode, menuCode, "受治理新页面", menuPath,
                "LayoutDashboard", permissionCode);
        return new Fixture(source, request, pageKey, menuCode, menuPath, modelCode, parentId);
    }

    private PageSchema insertSourcePage() {
        String pid = UniqueIdGenerator.generate();
        PageSchema page = new PageSchema();
        page.setPid(pid);
        page.setTenantId(getTestTenant().getId());
        page.setEnvId(MetaContext.getCurrentEnvironmentId());
        page.setPageKey("authoring_source_" + pid.toLowerCase());
        page.setName("Authoring source " + pid);
        page.setKind("list");
        page.setSchemaVersion(4);
        page.setProfile("admin");
        page.setTitle("{\"zh-CN\":\"Source\"}");
        page.setLayout("{\"type\":\"stack\"}");
        page.setBlocks("[]");
        page.setStatus("published");
        page.setOwnershipScope("TENANT");
        page.setVersion(1);
        page.setRowVersion(1);
        page.setIsCurrent(true);
        page.setDeletedFlag(false);
        pageSchemaMapper.insert(page);
        return page;
    }

    private CreateNewPageWorkspaceRequest requestWithKind(
            CreateNewPageWorkspaceRequest source,
            String kind) {
        return new CreateNewPageWorkspaceRequest(
                source.expectedSourceRevision(), source.pageKey(), source.name(), source.title(),
                source.description(), kind, source.modelCode(), source.parentMenuCode(),
                source.menuCode(), source.menuName(), source.menuPath(), source.menuIcon(),
                source.permissionCode());
    }

    private CreateNewPageWorkspaceRequest requestWithModel(
            CreateNewPageWorkspaceRequest source,
            String modelCode) {
        return new CreateNewPageWorkspaceRequest(
                source.expectedSourceRevision(), source.pageKey(), source.name(), source.title(),
                source.description(), source.kind(), modelCode, source.parentMenuCode(),
                source.menuCode(), source.menuName(), source.menuPath(), source.menuIcon(),
                source.permissionCode());
    }

    private long insertParentMenu(String code) {
        long id = idGenerator.nextId(code);
        jdbcTemplate.update("""
                INSERT INTO ab_menu (
                    id, pid, tenant_id, code, name, path, type, visible, order_no,
                    status, deleted_flag, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'Authoring', ?, 0, TRUE, 0,
                        'active', FALSE, ?, ?)
                """, id, UniqueIdGenerator.generate(), getTestTenant().getId(), code,
                "/" + code, getTestUser().getId(), getTestUser().getId());
        return id;
    }

    private void insertPermission(String code) {
        jdbcTemplate.update("""
                INSERT INTO ab_permission (
                    pid, tenant_id, code, name, resource_type, resource_code, action,
                    source, status, deleted_flag)
                VALUES (?, ?, ?, 'Authoring page read', 'PAGE', ?, 'read',
                        'test', 'active', FALSE)
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), code, code);
    }

    private void insertModel(String code) {
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, extension, version, is_current,
                    row_version, status, deleted_flag)
                VALUES (?, ?, ?, ?, jsonb_build_object('displayName', 'Authoring model'),
                        1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), code,
                "mt_" + code);
    }

    private void insertLeafMenu(String code, String path, long parentId, String pagePid) {
        jdbcTemplate.update("""
                INSERT INTO ab_menu (
                    id, pid, tenant_id, parent_id, code, name, path, type, visible, order_no,
                    page_pid, status, deleted_flag, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Conflict', ?, 1, TRUE, 0,
                        ?, 'active', FALSE, ?, ?)
                """, idGenerator.nextId(code), UniqueIdGenerator.generate(),
                getTestTenant().getId(), parentId, code, path, pagePid,
                getTestUser().getId(), getTestUser().getId());
    }

    private void approveAsDifferentActor(SessionView created) {
        long envId = MetaContext.getCurrentEnvironmentId();
        try {
            MetaContext.setContext(
                    getTestTenant().getId(), getTestUser().getId() + 90_000,
                    "reviewer", "reviewer");
            MetaContext.setEnvironmentId(envId);
            governanceService.approve(
                    created.changeSetPid(), new ReviewRequest(3, "reviewed new resource"));
        } finally {
            applyTestMetaContext();
        }
    }

    private int countPage(String pageKey) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_page_schema
                WHERE tenant_id = ? AND env_id = ? AND page_key = ? AND deleted_flag = FALSE
                """, Integer.class, getTestTenant().getId(),
                MetaContext.getCurrentEnvironmentId(), pageKey);
    }

    private int countMenu(String menuCode) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_menu
                WHERE tenant_id = ? AND code = ? AND deleted_flag = FALSE
                """, Integer.class, getTestTenant().getId(), menuCode);
    }

    private java.util.List<String> changeItemPaths(String changeSetPid) {
        return jdbcTemplate.queryForList("""
                SELECT item.property_path FROM ab_authoring_change_item item
                JOIN ab_authoring_change_set change_set ON change_set.id = item.change_set_id
                WHERE change_set.pid = ? ORDER BY item.base_revision
                """, String.class, changeSetPid);
    }

    private record Fixture(
            SessionView source,
            CreateNewPageWorkspaceRequest request,
            String pageKey,
            String menuCode,
            String menuPath,
            String modelCode,
            long parentMenuId) {
    }
}
