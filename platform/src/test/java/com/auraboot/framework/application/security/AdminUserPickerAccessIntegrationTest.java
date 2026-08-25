package com.auraboot.framework.application.security;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.integration.security.AdminGuardTestSupport;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.exception.UserException;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP + PostgreSQL authorization contract for the tenant-scoped user picker APIs.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Admin user picker reads are available to authenticated tenant members")
@Import(AdminUserPickerAccessIntegrationTest.ClassLevelAuthenticatedController.class)
class AdminUserPickerAccessIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserService userService;
    @Autowired private AdminRoleChecker adminRoleChecker;

    private Long tenantId;
    private Long otherTenantId;
    private User otherTenantUser;

    @BeforeEach
    void seedRoleLessTenantMembers() throws UserException {
        tenantId = TestIdGenerator.uniqueTenantId();
        otherTenantId = TestIdGenerator.uniqueTenantId();
        seedTenantMember(tenantId, testUser.getId());

        String unique = UniqueIdGenerator.generate().toLowerCase();
        otherTenantUser = userService.signUp(
                "picker-isolated-" + unique + "@test.local",
                "Test2026x!",
                "picker-isolated-" + unique);
        seedTenantMember(otherTenantId, otherTenantUser.getId());
    }

    @AfterEach
    void cleanup() {
        adminRoleChecker.invalidateAll();
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_admin_action_log WHERE tenant_id = ?", tenantId);
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
        }
        if (otherTenantId != null) {
            AdminGuardTestSupport.cleanupTenant(jdbc, otherTenantId);
        }
        if (otherTenantUser != null) {
            jdbc.update("DELETE FROM ab_user WHERE id = ?", otherTenantUser.getId());
        }
    }

    @Test
    @DisplayName("role-less member gets tenant picker reads only; management remains denied; admin still works")
    void pickerReadContractIsExactAndTenantScoped() throws Exception {
        MockMvc memberMvc = mockMvcForTenant(tenantId);

        memberMvc.perform(get("/api/admin/users/search")
                        .param("keyword", testUser.getUserName())
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[*].pid", hasItem(testUser.getPid())));

        memberMvc.perform(get("/api/admin/users/{userPid}", testUser.getPid())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pid").value(testUser.getPid()));

        memberMvc.perform(get("/api/admin/users/search")
                        .param("keyword", otherTenantUser.getEmail())
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[*].pid", not(hasItem(otherTenantUser.getPid()))));

        memberMvc.perform(get("/api/admin/users/{userPid}", otherTenantUser.getPid())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", not("0")));

        memberMvc.perform(get("/api/admin/users/employee-accounts/import/template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));

        memberMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));

        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());
        adminRoleChecker.invalidateAll();

        mockMvcForTenant(tenantId).perform(get("/api/admin/users/search")
                        .param("keyword", testUser.getUserName())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[*].pid", hasItem(testUser.getPid())));
    }

    @Test
    @DisplayName("class-level AuthenticatedAccess does not bypass the admin role guard")
    void classLevelAuthenticatedAccessRemainsDenied() throws Exception {
        mockMvcForTenant(tenantId)
                .perform(get("/api/admin/class-level-authenticated/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    private MockMvc mockMvcForTenant(Long currentTenantId) {
        return AdminGuardTestSupport.buildMockMvc(
                webApplicationContext,
                currentTenantId,
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName());
    }

    private void seedTenantMember(Long currentTenantId, Long userId) {
        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE)",
                currentTenantId, UniqueIdGenerator.generate(), "guard_test_" + currentTenantId);
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                TestIdGenerator.uniqueUserId(), UniqueIdGenerator.generate(), currentTenantId, userId);
    }

    @RestController
    @RequestMapping("/api/admin/class-level-authenticated")
    @AuthenticatedAccess("negative probe: class-level annotations must not bypass admin role checks")
    static class ClassLevelAuthenticatedController {

        @GetMapping("/probe")
        ApiResponse<String> probe() {
            return ApiResponse.success("unexpected");
        }
    }
}
