package com.auraboot.framework.integration.tenant;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.dto.TeamCreateRequest;
import com.auraboot.framework.organization.dto.TeamMemberAddRequest;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.handler.TenantMemberCommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.FieldMapPhase;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TenantMemberController - Integration Tests")
class TenantMemberControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamMemberService teamMemberService;

    @Autowired private UserService users;
    @Autowired private TenantMemberMapper memberMapper;
    @Autowired private PermissionMapper permissions;
    @Autowired private RolePermissionMapper grants;
    @Autowired private UserPermissionService userPermissions;
    @Autowired private DataScopeService scopes;
    @Autowired private MetaModelMapper models;
    @Autowired private MetaModelService modelService;
    @Autowired private FieldMapPhase fieldMapPhase;
    @Autowired private TenantMemberCommandHandler memberCommandHandler;

    private MockMvc mockMvc;

    private String teamAlphaPid;
    private String teamBravoPid;

    private final String runId = String.valueOf(System.nanoTime());

    @ParameterizedTest(name = "domain-owned removal preserves target until handler; self={0}")
    @CsvSource({"false", "true"})
    void removalPreservesMemberUntilDomainChecks(boolean self) {
        prepareMemberModel();
        grantMemberAction("read");
        grantMemberAction("delete");
        TenantMember target = self
                ? tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), getTestUser().getId())
                : createMemberFixture(false);
        applyTestMetaContext();
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete");
        request.setTargetRecordId(target.getPid());
        CommandDefinition command = new CommandDefinition();
        command.setCode("admin:delete_member");
        command.setModelCode("tenant_member");
        BindingRule rule = new BindingRule();
        rule.setHandlerClass("tenantMemberCommandHandler");
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode(command.getCode()).command(command).request(request)
                .tenantId(getTestTenant().getId()).userId(getTestUser().getId())
                .startTime(System.currentTimeMillis()).payload(new HashMap<>())
                .execConfig(new HashMap<>(Map.of("type", "delete")))
                .rulesByType(Map.of("handler", List.of(rule))).build();

        fieldMapPhase.execute(ctx);

        assertThat(tenantMemberService.findByPid(target.getPid())).isNotNull()
                .extracting(TenantMember::getStatus).isEqualTo(target.getStatus());
        assertThat(ctx.getFieldMapResults()).isEmpty();
        CommandHandlerContext handlerContext = CommandHandlerContext.builder()
                .commandCode(command.getCode()).targetRecordId(target.getPid())
                .tenantId(getTestTenant().getId()).userId(getTestUser().getId())
                .payload(Map.of()).fieldMapResults(ctx.getFieldMapResults()).build();
        if (self) {
            assertThatThrownBy(() -> memberCommandHandler.execute(handlerContext))
                    .isInstanceOf(BusinessException.class);
            assertThat(tenantMemberService.findByPid(target.getPid())).isNotNull();
        } else {
            assertThat(memberCommandHandler.execute(handlerContext))
                    .containsEntry("removed", true).containsEntry("handlerExecuted", true);
            assertThat(tenantMemberService.findByPid(target.getPid())).isNull();
        }
    }

    @BeforeEach
    void setup() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Ensure test user is a tenant member
        if (tenantMemberService.findByTenantIdAndUserId(tenantId, userId) == null) {
            tenantMemberService.addMember(userId, tenantId, "active");
        }

        // Set context so TeamMemberService.addMember can read tenantId
        MetaContext.setContext(tenantId, userId, getTestUser().getPid(), getTestUser().getUserName());

        // Create two distinct teams for this test run
        teamAlphaPid = ensureTeam("ctrl-alpha-" + runId, "Ctrl Alpha " + runId, tenantId, userId);
        teamBravoPid = ensureTeam("ctrl-bravo-" + runId, "Ctrl Bravo " + runId, tenantId, userId);

        // Add test user to both teams
        ensureUserInTeam(userId, teamAlphaPid);
        ensureUserInTeam(userId, teamBravoPid);

        MetaContext.clear();

        // Set up MockMvc with a filter that injects MetaContext per request
        Filter metaContextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails principal = new CustomUserDetails(getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(), AuthorityUtils.NO_AUTHORITIES,
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    @Test
    @DisplayName("GET /api/tenant/members/current/teams should return PIDs of teams the user belongs to")
    void getCurrentUserTeams() throws Exception {
        mockMvc.perform(get("/api/tenant/members/current/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@=='" + teamAlphaPid + "')]").exists())
                .andExpect(jsonPath("$.data[?(@=='" + teamBravoPid + "')]").exists());
    }

    @Test
    @DisplayName("legacy email-based member import routes are removed")
    void legacyMemberImportRoutesAreRemoved() throws Exception {
        mockMvc.perform(get("/api/tenant/members/import/template"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tenant/members/import")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/tenant/members/import-rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isMethodNotAllowed());
    }

    @ParameterizedTest(name = "{1} preflight requires only its own action and rejects revocation")
    @CsvSource({"suspend,suspend,remove,delete", "leave,deactivate,remove,delete", "delete,remove,deactivate,leave"})
    void memberActionOnlyPreflightAllowsAndThenRejectsRevokedGrant(
            String verb, String action, String otherAction, String otherVerb) throws Exception {
        prepareMemberModel();
        grantMemberAction("read");
        RolePermission actionGrant = grantMemberAction(verb);
        TenantMember target = createMemberFixture(false);
        assertThat(userPermissions.getUserPermissionCodes(getTestUser().getId()))
                .contains("model.tenant_member.read", "model.tenant_member." + verb)
                .doesNotContain("admin_tenant_member", "org.role.update", "model.tenant_member." + otherVerb);
        String api = "/api/tenant/members/" + target.getPid();
        mockMvc.perform(get(api + "/offboarding-impact").param("action", action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.memberPid").value(target.getPid()))
                .andExpect(jsonPath("$.data.resources").isArray());
        mockMvc.perform(get(api + "/offboarding-candidates").param("action", action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(get(api + "/offboarding-impact").param("action", otherAction))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(api + "/offboarding-candidates").param("action", otherAction))
                .andExpect(status().isForbidden());
        applyTestMetaContext();
        grants.deleteById(actionGrant.getId());
        evictMemberGrants();
        assertThat(userPermissions.hasPermission(getTestUser().getId(), "model.tenant_member." + verb)).isFalse();
        mockMvc.perform(get(api + "/offboarding-impact").param("action", action))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(api + "/offboarding-candidates").param("action", action))
                .andExpect(status().isForbidden());
        applyTestMetaContext();
        assertThat(tenantMemberService.findByPid(target.getPid()).getStatus()).isEqualTo("active");
    }

    @ParameterizedTest(name = "{1} impact and candidates honor the real self scope")
    @CsvSource({"suspend,suspend", "leave,deactivate", "delete,remove"})
    void memberPreflightAndRecipientsHonorRealSelfScope(String verb, String action) throws Exception {
        prepareMemberModel();
        grantMemberAction("read");
        grantMemberAction(verb);
        TenantMember owned = createMemberFixture(true);
        TenantMember hidden = createMemberFixture(false);
        scopes.setScope(getTestTenant().getId(), getTestRole().getId(), "tenant_member", "read", "self", "MAX");
        assertThat(scopes.resolveScope(testTenantMember.getId(), "tenant_member", "read").scopeType())
                .isEqualTo("self");
        mockMvc.perform(get("/api/tenant/members/" + owned.getPid() + "/offboarding-impact").param("action", action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(get("/api/tenant/members/" + hidden.getPid() + "/offboarding-impact").param("action", action))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenant/members/" + hidden.getPid() + "/offboarding-candidates").param("action", action))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenant/members/" + owned.getPid() + "/offboarding-candidates").param("action", action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[?(@.memberPid == '" + testTenantMember.getPid() + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.memberPid == '" + hidden.getPid() + "')]").isEmpty());
    }

    @Test
    void nativeMemberDetailHonorsReadSelfScope() throws Exception {
        prepareMemberModel();
        grantMemberAction("read");
        TenantMember owned = createMemberFixture(true);
        TenantMember hidden = createMemberFixture(false);
        scopes.setScope(getTestTenant().getId(), getTestRole().getId(), "tenant_member", "read", "self", "MAX");
        assertThat(userPermissions.getUserPermissionCodes(getTestUser().getId()))
                .contains("model.tenant_member.read").doesNotContain("admin_tenant_member");
        mockMvc.perform(get("/api/tenant/members/" + owned.getPid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pid").value(owned.getPid()));
        mockMvc.perform(get("/api/tenant/members/" + hidden.getPid()))
                .andExpect(status().isForbidden());
    }

    @Test
    void nativeMemberDetailRejectsMissingReadGrant() throws Exception {
        prepareMemberModel();
        TenantMember target = createMemberFixture(true);
        evictMemberGrants();
        assertThat(userPermissions.getUserPermissionCodes(getTestUser().getId()))
                .doesNotContain("model.tenant_member.read", "admin_tenant_member");
        mockMvc.perform(get("/api/tenant/members/" + target.getPid()))
                .andExpect(status().isForbidden());
    }

    @Test
    void nativeMemberTeamsHonorsReadSelfScope() throws Exception {
        prepareMemberModel();
        grantMemberAction("read");
        TenantMember owned = createMemberFixture(true);
        TenantMember hidden = createMemberFixture(false);
        scopes.setScope(getTestTenant().getId(), getTestRole().getId(), "tenant_member", "read", "self", "MAX");
        mockMvc.perform(get("/api/tenant/members/" + owned.getPid() + "/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/tenant/members/" + hidden.getPid() + "/teams"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nativeMemberLegacyAdminRetainsTenantWideRead() throws Exception {
        prepareMemberModel();
        grantMemberPermission("admin_tenant_member", "manage");
        TenantMember target = createMemberFixture(false);
        scopes.setScope(getTestTenant().getId(), getTestRole().getId(), "tenant_member", "read", "self", "MAX");
        assertThat(userPermissions.getUserPermissionCodes(getTestUser().getId()))
                .contains("admin_tenant_member").doesNotContain("model.tenant_member.read");
        mockMvc.perform(get("/api/tenant/members/" + target.getPid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pid").value(target.getPid()));
        mockMvc.perform(get("/api/tenant/members/" + target.getPid() + "/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }

    private void prepareMemberModel() {
        applyTestMetaContext();
        Model model = models.findCurrentByCode("tenant_member");
        if (model == null) {
            model = new Model();
            model.setPid(UniqueIdGenerator.generate());
            model.setTenantId(getTestTenant().getId());
            model.setCode("tenant_member");
            model.setTableName("ab_tenant_member");
            model.setSourceType("physical");
            model.setVersion(1);
            model.setIsCurrent(true);
            model.setStatus("published");
            model.setDeletedFlag(false);
            model.setCreatedAt(Instant.now());
            model.setUpdatedAt(Instant.now());
            ExtensionBean extension = new ExtensionBean();
            extension.setExtension(Map.of("modelType", "entity", "displayName", "Tenant member"));
            model.setExtension(extension);
            models.insert(model);
        }
        assertThat(model.getTableName()).isEqualTo("ab_tenant_member");
        modelService.refreshModelCache("tenant_member");
        // Reset the real scope and its cache before each case; rolled-back rows alone
        // do not invalidate the previous case's cached self condition.
        scopes.setScope(getTestTenant().getId(), getTestRole().getId(), "tenant_member", "read", "all", "MAX");
    }

    private TenantMember createMemberFixture(boolean owned) {
        applyTestMetaContext();
        var user = users.signUp("offboarding-" + UniqueIdGenerator.generate() + "@example.test", "Test-password-2026!");
        TenantMember member = tenantMemberService.addMember(user.getId(), getTestTenant().getId(), "active");
        member.setCreatedBy(owned ? getTestUser().getId() : user.getId());
        memberMapper.updateById(member);
        return member;
    }

    private RolePermission grantMemberAction(String action) {
        return grantMemberPermission("model.tenant_member." + action, action);
    }

    private RolePermission grantMemberPermission(String code, String action) {
        applyTestMetaContext();
        Permission permission = permissions.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setTenantId(getTestTenant().getId());
            permission.setCode(code);
            permission.setName("Tenant member " + action);
            permission.setResourceType("model");
            permission.setResourceCode("tenant_member");
            permission.setAction(action);
            permission.setSource("integration_test");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissions.insert(permission);
        }
        RolePermission grant = new RolePermission();
        grant.setPid(UniqueIdGenerator.generate());
        grant.setTenantId(getTestTenant().getId());
        grant.setRoleId(getTestRole().getId());
        grant.setPermissionId(permission.getId());
        grant.setGrantType("grant");
        grant.setStatus("active");
        grant.setDeletedFlag(false);
        grant.setCreatedAt(Instant.now());
        grant.setUpdatedAt(Instant.now());
        grants.insert(grant);
        evictMemberGrants();
        return grant;
    }

    private void evictMemberGrants() {
        userPermissions.evictPermissionDefinitions(getTestTenant().getId());
        userPermissions.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }

    // ===== helpers =====

    private String ensureTeam(String code, String name, Long tenantId, Long operatorId) {
        try {
            TeamCreateRequest req = new TeamCreateRequest();
            req.setCode(code);
            req.setName(name);
            return teamService.createTeam(req, tenantId, operatorId).getPid();
        } catch (Exception e) {
            return teamService.lambdaQuery()
                    .eq(com.auraboot.framework.organization.entity.Team::getTenantId, tenantId)
                    .eq(com.auraboot.framework.organization.entity.Team::getCode, code)
                    .one()
                    .getPid();
        }
    }

    private void ensureUserInTeam(Long userId, String teamPid) {
        try {
            TeamMemberAddRequest req = new TeamMemberAddRequest();
            req.setUserId(userId);
            req.setRole("member");
            teamMemberService.addMember(teamPid, req, userId);
        } catch (Exception e) {
            // Already a member — ignore
        }
    }
}
