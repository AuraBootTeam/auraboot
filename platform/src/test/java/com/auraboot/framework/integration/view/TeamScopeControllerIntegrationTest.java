package com.auraboot.framework.integration.view;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TEAM Scope Controller Guard - Integration Tests")
class TeamScopeControllerIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM_ALPHA = "team_alpha";
    private static final String TEAM_BRAVO = "team_bravo";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private TenantMemberService tenantMemberService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        grantPermission("system.saved_view.update", "system", "saved_view", "update", "Saved View Update");
        userPermissionService.evictUserPermissions(getTestUser().getId());

        ensureUserInSingleTeam(getTestUser().getId(), TEAM_ALPHA);

        Filter contextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true
                );
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    @Test
    @DisplayName("POST /api/views should reject TEAM create when current user is not in team")
    void savedViewCreateShouldRejectNonTeamMember() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "controller-team-view-" + System.nanoTime());
        payload.put("modelCode", "device");
        payload.put("pageKey", "controller-team-test");
        payload.put("scope", "team");
        payload.put("teamId", TEAM_BRAVO);

        mockMvc.perform(post("/api/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("not a member of team")));
    }

    @Test
    @DisplayName("POST /api/views with TEAM scope but missing teamId should return 422")
    void savedViewCreateShouldRejectMissingTeamId() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "controller-team-view-noid-" + System.nanoTime());
        payload.put("modelCode", "device");
        payload.put("pageKey", "controller-team-test-noid");
        payload.put("scope", "team");
        // intentionally omit teamId

        mockMvc.perform(post("/api/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Team ID is required")));
    }

    private void grantPermission(String code, String resourceType, String resourceCode, String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }

        RolePermission rolePermission = new RolePermission();
        rolePermission.setPid(UniqueIdGenerator.generate());
        rolePermission.setRoleId(getTestRole().getId());
        rolePermission.setPermissionId(permission.getId());
        rolePermission.setGrantType("grant");
        rolePermission.setStatus("active");
        rolePermission.setDeletedFlag(false);
        rolePermission.setTenantId(getTestTenant().getId());
        rolePermission.setCreatedAt(Instant.now());
        rolePermission.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(rolePermission);
    }

    private void ensureUserInSingleTeam(Long userId, String teamId) {
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), userId);
        if (member == null) {
            tenantMemberService.addMember(userId, getTestTenant().getId(), "active");
            member = tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), userId);
        }
        member.setStatus("active");
        member.setSettings("{\"teamIds\":[\"" + teamId + "\"]}");
        member.setUpdatedAt(Instant.now());
        member.setUpdatedBy(getTestUser().getId());
        tenantMemberService.updateMember(member);
    }
}
