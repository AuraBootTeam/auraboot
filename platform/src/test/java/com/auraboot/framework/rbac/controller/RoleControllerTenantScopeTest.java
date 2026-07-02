package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.rbac.dto.RoleResponse;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tenant-scope tests for RoleController pid resolution.
 *
 * <p>Security regression: {@code ab_role} is excluded from the tenant line interceptor,
 * and the controller resolved every operation via the global {@code findByPid(pid)}, so a
 * tenant-A admin who knew a tenant-B role pid could read/mutate it. Resolution now
 * re-asserts tenant ownership (cross-tenant → not-found), while system roles (tenantId
 * null) stay accessible.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleController tenant-scoped pid resolution")
class RoleControllerTenantScopeTest {

    @Mock
    private RoleService roleService;
    @Mock
    private RolePermissionService rolePermissionService;
    @Mock
    private PluginResourceTracker pluginResourceTracker;

    @InjectMocks
    private RoleController controller;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private Role role(Long tenantId, String pid) {
        Role r = new Role();
        r.setId(5L);
        r.setPid(pid);
        r.setTenantId(tenantId);
        r.setCode("role-code");
        r.setName("Role Name");
        return r;
    }

    @Test
    @DisplayName("cross-tenant role pid resolves to not-found")
    void getRole_crossTenant_notFound() {
        MetaContext.setContext(1L, 100L, "u", "user");
        when(roleService.findByPid("r-other")).thenReturn(role(2L, "r-other"));
        assertThrows(RootUnCheckedException.class, () -> controller.getRole("r-other"));
    }

    @Test
    @DisplayName("own-tenant role is accessible")
    void getRole_sameTenant_ok() {
        MetaContext.setContext(1L, 100L, "u", "user");
        when(roleService.findByPid("r-own")).thenReturn(role(1L, "r-own"));
        ApiResponse<RoleResponse> r = controller.getRole("r-own");
        assertTrue(r.isSuccess());
    }

    @Test
    @DisplayName("system role (tenantId null) stays accessible")
    void getRole_systemRole_accessible() {
        MetaContext.setContext(1L, 100L, "u", "user");
        when(roleService.findByPid("r-sys")).thenReturn(role(null, "r-sys"));
        ApiResponse<RoleResponse> r = controller.getRole("r-sys");
        assertTrue(r.isSuccess());
    }
}
