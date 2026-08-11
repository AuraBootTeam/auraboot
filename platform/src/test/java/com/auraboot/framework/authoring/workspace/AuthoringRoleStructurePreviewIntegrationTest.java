package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real-PostgreSQL proof for the governed, non-impersonating role structure preview. */
class AuthoringRoleStructurePreviewIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthoringWorkspaceService workspaceService;
    @Autowired
    private PageSchemaMapper pageSchemaMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserPermissionService userPermissionService;
    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    @Test
    void usesRealRoleBindingsWithoutImpersonationOrRecordLeakage() throws Exception {
        grantActorPermissions();
        Long targetRoleId = positiveRandomId();
        String targetRolePid = UniqueIdGenerator.generate();
        insertTargetRole(targetRoleId, targetRolePid);
        Long publicPermissionId = permissionId("customer.public.read");
        Long secretPermissionId = ensurePermission(
                "customer.secret.read", "customer.secret", "read", "Customer Secret Read");
        bindRolePermission(targetRoleId, publicPermissionId);
        bindRolePermission(targetRoleId, secretPermissionId);

        PageSchema page = insertPermissionPage();
        insertMenuPath(page.getPid(), targetRolePid);
        userPermissionService.evictPermissionDefinitions(getTestTenant().getId());
        userPermissionService.evictRoleUsers(getTestTenant().getId(), targetRoleId);
        userPermissionService.evictUserPermissions(
                getTestTenant().getId(), getTestUser().getId());
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        mockMvc.perform(get(
                        "/api/authoring/sessions/{sessionPid}/role-preview-targets",
                        opened.sessionPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.rolePid == '%s')].roleName"
                        .formatted(targetRolePid)).value("Authoring Operator"));

        String response = mockMvc.perform(get(
                        "/api/authoring/sessions/{sessionPid}/role-structure-preview",
                        opened.sessionPid())
                        .param("rolePid", targetRolePid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("STRUCTURE"))
                .andExpect(jsonPath("$.data.actorIntersectionApplied").value(true))
                .andExpect(jsonPath("$.data.businessDataIncluded").value(false))
                .andExpect(jsonPath("$.data.exportAllowed").value(false))
                .andExpect(jsonPath("$.data.businessActionsAllowed").value(false))
                .andExpect(jsonPath("$.data.snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.decisions[?(@.nodeId == 'public-field')].reason")
                        .value("ALLOW"))
                .andExpect(jsonPath("$.data.decisions[?(@.nodeId == 'secret-field')].allowed")
                        .value(false))
                .andExpect(jsonPath("$.data.decisions[?(@.nodeId == 'secret-field')].reason")
                        .value("ACTOR_SCOPE_LIMIT"))
                .andExpect(jsonPath("$.data.decisions[?(@.nodeId == 'delete-action')].reason")
                        .value("TARGET_ROLE_DENY"))
                .andExpect(jsonPath("$.data.decisions[?(@.nodeId == 'menu-parent')].reason")
                        .value("ALLOW"))
                .andExpect(jsonPath("$.data.decisions[?(@.nodeId == 'menu-child')].reason")
                        .value("ACTOR_SCOPE_LIMIT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response)
                .doesNotContain("TOP-SECRET-RECORD")
                .doesNotContain("real-record-7")
                .doesNotContain("rows");

        String foreignRolePid = insertForeignRole();
        mockMvc.perform(get(
                        "/api/authoring/sessions/{sessionPid}/role-structure-preview",
                        opened.sessionPid())
                        .param("rolePid", foreignRolePid))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatesSyntheticRowsInMemoryWithoutTenantRecordOrSideEffects() throws Exception {
        grantActorPermissions();
        PageSchema page = insertPermissionPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        int outboxBefore = tableCount("ab_outbox");
        int behaviorOutboxBefore = tableCount("ab_behavior_outcome_outbox");
        int messageBefore = tableCount("ab_im_message");
        int webhookBefore = tableCount("ab_webhook_delivery_log");

        String response = mockMvc.perform(get(
                        "/api/authoring/sessions/{sessionPid}/synthetic-preview",
                        opened.sessionPid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SYNTHETIC"))
                .andExpect(jsonPath("$.data.source").value("GENERATED_IN_MEMORY"))
                .andExpect(jsonPath("$.data.isolatedFromTenantData").value(true))
                .andExpect(jsonPath("$.data.persisted").value(false))
                .andExpect(jsonPath("$.data.exportAllowed").value(false))
                .andExpect(jsonPath("$.data.businessActionsAllowed").value(false))
                .andExpect(jsonPath("$.data.records.length()").value(3))
                .andExpect(jsonPath("$.data.records[0].pid").value("synthetic-001"))
                .andExpect(jsonPath("$.data.records[0].name").value("Sample name 01"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("TOP-SECRET-RECORD")
                .doesNotContain("real-record-7");
        assertThat(tableCount("ab_outbox")).isEqualTo(outboxBefore);
        assertThat(tableCount("ab_behavior_outcome_outbox")).isEqualTo(behaviorOutboxBefore);
        assertThat(tableCount("ab_im_message")).isEqualTo(messageBefore);
        assertThat(tableCount("ab_webhook_delivery_log")).isEqualTo(webhookBefore);
    }

    @Test
    void identitySimulationIsActorBoundReadonlyLimitedAndFullyLifecycleAudited() throws Exception {
        grantActorPermissions();
        grantCommittedPermissionToTestRole(
                MetaPermission.META_AUDIT_TRAIL_ADMIN,
                "audit", "identity_simulation", "admin", "Identity Simulation Admin");
        Long targetRoleId = positiveRandomId();
        String targetRolePid = UniqueIdGenerator.generate();
        insertTargetRole(targetRoleId, targetRolePid);
        bindRolePermission(targetRoleId, permissionId("customer.public.read"));
        userPermissionService.evictRoleUsers(getTestTenant().getId(), targetRoleId);
        userPermissionService.evictUserPermissions(
                getTestTenant().getId(), getTestUser().getId());
        PageSchema page = insertPermissionPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));

        String started = mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/identity-simulations",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rolePid":"%s","durationMinutes":5,
                                 "reason":"incident-742 permission review"}
                                """.formatted(targetRolePid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("AUDITED_IDENTITY"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.actorIntersectionApplied").value(true))
                .andExpect(jsonPath("$.data.businessDataIncluded").value(false))
                .andExpect(jsonPath("$.data.readOnly").value(true))
                .andExpect(jsonPath("$.data.exportAllowed").value(false))
                .andExpect(jsonPath("$.data.businessActionsAllowed").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String simulationPid = objectMapper.readTree(started).path("data").path("simulationPid")
                .asText();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_identity_simulation
                WHERE tenant_id = ? AND actor_user_id = ?
                  AND pid = ? AND status = 'ACTIVE' AND reason = ?
                """, Integer.class,
                getTestTenant().getId(), getTestUser().getId(), simulationPid,
                "incident-742 permission review"))
                .isEqualTo(1);
        assertThat(auditEventCount(simulationPid, "IDENTITY_SIMULATION_STARTED")).isEqualTo(1);
        assertThat(auditMetadata(simulationPid))
                .contains("readOnly")
                .contains(targetRolePid)
                .doesNotContain("incident-742 permission review");

        mockMvc.perform(get("/api/authoring/identity-simulations/{pid}", simulationPid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.decisions.length()").value(5));
        assertThat(auditEventCount(simulationPid, "IDENTITY_SIMULATION_ACCESSED")).isEqualTo(1);

        mockMvc.perform(post("/api/authoring/identity-simulations/{pid}/end", simulationPid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.decisions.length()").value(0));
        assertThat(auditEventCount(simulationPid, "IDENTITY_SIMULATION_ENDED")).isEqualTo(1);
    }

    @Test
    void identitySimulationExpiresAndCannotBeReadUnderAnotherActor() throws Exception {
        grantActorPermissions();
        grantCommittedPermissionToTestRole(
                MetaPermission.META_AUDIT_TRAIL_ADMIN,
                "audit", "identity_simulation", "admin", "Identity Simulation Admin");
        Long targetRoleId = positiveRandomId();
        String targetRolePid = UniqueIdGenerator.generate();
        insertTargetRole(targetRoleId, targetRolePid);
        PageSchema page = insertPermissionPage();
        SessionView opened = workspaceService.open(new OpenSessionRequest(page.getPid(), null));
        String started = mockMvc.perform(post(
                        "/api/authoring/sessions/{sessionPid}/identity-simulations",
                        opened.sessionPid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rolePid":"%s","durationMinutes":1,"reason":"ttl proof"}
                                """.formatted(targetRolePid)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String simulationPid = objectMapper.readTree(started).path("data").path("simulationPid")
                .asText();

        jdbcTemplate.update("""
                UPDATE ab_authoring_identity_simulation
                SET started_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE pid = ?
                """, simulationPid);
        mockMvc.perform(get("/api/authoring/identity-simulations/{pid}", simulationPid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.decisions.length()").value(0));
        assertThat(auditEventCount(simulationPid, "IDENTITY_SIMULATION_EXPIRED")).isEqualTo(1);

        jdbcTemplate.update("""
                UPDATE ab_authoring_identity_simulation
                SET actor_user_id = actor_user_id + 100000
                WHERE pid = ?
                """, simulationPid);
        mockMvc.perform(get("/api/authoring/identity-simulations/{pid}", simulationPid))
                .andExpect(status().isNotFound());
    }

    private void grantActorPermissions() {
        grantCommittedPermissionToTestRole(
                MetaPermission.PAGE_DESIGNER_MANAGE,
                "meta", "designer", "update", "Page Designer Manage");
        grantCommittedPermissionToTestRole(
                "customer.public.read",
                "model", "customer.public", "read", "Customer Public Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void insertTargetRole(Long targetRoleId, String targetRolePid) {
        jdbcTemplate.update("""
                INSERT INTO ab_role (
                    id, pid, tenant_id, name, code, priority, status, deleted_flag)
                VALUES (?, ?, ?, 'Authoring Operator', ?, 25, 'active', FALSE)
                    """, targetRoleId, targetRolePid, getTestTenant().getId(),
                "authoring_operator_" + targetRolePid.toLowerCase());
    }

    private PageSchema insertPermissionPage() {
        ensureModel("test_model");
        String pid = UniqueIdGenerator.generate();
        PageSchema page = new PageSchema();
        page.setPid(pid);
        page.setTenantId(getTestTenant().getId());
        page.setEnvId(MetaContext.getCurrentEnvironmentId());
        page.setPageKey("authoring_role_" + pid.toLowerCase());
        page.setModelCode("test_model");
        page.setName("Authoring role preview");
        page.setKind("list");
        page.setSchemaVersion(3);
        page.setProfile("admin");
        page.setTitle("{\"en-US\":\"Role preview\"}");
        page.setLayout("{}");
        page.setBlocks("""
                [{"id":"table-1","blockType":"table","props":{
                    "rows":[{"pid":"real-record-7","secret":"TOP-SECRET-RECORD"}]},
                  "blocks":[
                    {"id":"public-field","blockType":"field","field":"name",
                     "props":{"label":"Public name","permissionCode":"customer.public.read"}},
                    {"id":"secret-field","blockType":"field","field":"secret",
                     "props":{"label":"Secret name","permissionCode":"customer.secret.read"}},
                    {"id":"delete-action","blockType":"action","actionType":"delete",
                     "props":{"label":"Delete","permissionCode":"customer.delete"}}
                  ]}]
                """);
        page.setStatus("published");
        page.setVersion(1);
        page.setSemver("1.0.0");
        page.setRowVersion(1);
        page.setIsCurrent(true);
        page.setDeletedFlag(false);
        pageSchemaMapper.insert(page);
        return page;
    }

    private void ensureModel(String modelCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_meta_model
                WHERE tenant_id = ? AND code = ? AND is_current = TRUE
                  AND deleted_flag = FALSE
                """, Integer.class, getTestTenant().getId(), modelCode);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, version, is_current,
                    row_version, status, deleted_flag)
                VALUES (?, ?, ?, ?, 1, TRUE, 1, 'published', FALSE)
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), modelCode,
                "mt_" + modelCode);
    }

    private void insertMenuPath(String pagePid, String suffix) {
        Long parentId = positiveRandomId();
        jdbcTemplate.update("""
                INSERT INTO ab_menu (
                    id, pid, tenant_id, code, name, type, permission_code, visible,
                    order_no, status, deleted_flag)
                VALUES (?, 'menu-parent', ?, ?, 'Customer directory', 0,
                    'customer.public.read', TRUE, 5, 'active', FALSE)
                """, parentId, getTestTenant().getId(), "authoring_parent_" + suffix.toLowerCase());
        jdbcTemplate.update("""
                INSERT INTO ab_menu (
                    id, pid, tenant_id, parent_id, code, name, type, permission_code, visible,
                    order_no, page_pid, status, deleted_flag)
                VALUES (?, 'menu-child', ?, ?, ?, 'Customer menu', 1,
                    'customer.secret.read', TRUE, 10, ?, 'active', FALSE)
                """, positiveRandomId(), getTestTenant().getId(), parentId,
                "authoring_child_" + suffix.toLowerCase(), pagePid);
    }

    private Long ensurePermission(
            String code,
            String resourceCode,
            String action,
            String name) {
        jdbcTemplate.update("""
                INSERT INTO ab_permission (
                    pid, tenant_id, code, name, resource_type, resource_code,
                    action, source, status, deleted_flag)
                VALUES (?, ?, ?, ?, 'model', ?, ?, 'test', 'active', FALSE)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), code, name,
                resourceCode, action);
        return permissionId(code);
    }

    private Long permissionId(String code) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM ab_permission
                WHERE tenant_id = ? AND code = ? AND status = 'active' AND deleted_flag = FALSE
                """, Long.class, getTestTenant().getId(), code);
    }

    private void bindRolePermission(Long roleId, Long permissionId) {
        jdbcTemplate.update("""
                INSERT INTO ab_role_permission (
                    pid, tenant_id, role_id, permission_id, grant_type, status, deleted_flag)
                VALUES (?, ?, ?, ?, 'grant', 'active', FALSE)
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), roleId, permissionId);
    }

    private String insertForeignRole() {
        String foreignRolePid = UniqueIdGenerator.generate();
        Long foreignTenantId = positiveRandomId();
        jdbcTemplate.update("""
                INSERT INTO ab_tenant (
                    id, pid, name, display_name, status, deleted_flag)
                VALUES (?, ?, 'Foreign tenant', 'Foreign tenant', 'active', FALSE)
                """, foreignTenantId, UniqueIdGenerator.generate());
        jdbcTemplate.update("""
                INSERT INTO ab_role (
                    id, pid, tenant_id, name, code, priority, status, deleted_flag)
                VALUES (?, ?, ?, 'Foreign role', ?, 10, 'active', FALSE)
                """, positiveRandomId(), foreignRolePid, foreignTenantId,
                "foreign_" + foreignRolePid.toLowerCase());
        return foreignRolePid;
    }

    private int tableCount(String table) {
        String sql = switch (table) {
            case "ab_outbox" -> "SELECT COUNT(*) FROM ab_outbox";
            case "ab_behavior_outcome_outbox" ->
                    "SELECT COUNT(*) FROM ab_behavior_outcome_outbox";
            case "ab_im_message" -> "SELECT COUNT(*) FROM ab_im_message";
            case "ab_webhook_delivery_log" ->
                    "SELECT COUNT(*) FROM ab_webhook_delivery_log";
            default -> throw new IllegalArgumentException("Unsupported table: " + table);
        };
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private int auditEventCount(String simulationPid, String eventType) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ab_authoring_audit_event
                WHERE tenant_id = ? AND event_type = ?
                  AND metadata ->> 'simulationPid' = ?
                """, Integer.class,
                getTestTenant().getId(), eventType, simulationPid);
        return count == null ? 0 : count;
    }

    private String auditMetadata(String simulationPid) {
        return jdbcTemplate.queryForObject("""
                SELECT metadata::text FROM ab_authoring_audit_event
                WHERE tenant_id = ?
                  AND metadata ->> 'simulationPid' = ?
                ORDER BY created_at ASC LIMIT 1
                """, String.class,
                getTestTenant().getId(), simulationPid);
    }

    private static long positiveRandomId() {
        long value = java.util.UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }
}
