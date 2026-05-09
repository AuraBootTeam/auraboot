package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProvisioningServiceTest {

    @Mock private UserService userService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private RoleService roleService;
    @InjectMocks private UserProvisioningService service;

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setPid("u-pid-" + id);
        u.setEmail("e@x.com");
        return u;
    }

    private Role role(Long id, String code) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        return r;
    }

    private TenantMember member(Long id) {
        TenantMember m = new TenantMember();
        m.setId(id);
        return m;
    }

    private UserProvisionRequest req() {
        UserProvisionRequest r = new UserProvisionRequest();
        r.setEmail("e@x.com");
        r.setDisplayName("Display");
        return r;
    }

    @Test
    void provision_withInitialPasswordSkipsTempGeneration() throws Exception {
        UserProvisionRequest req = req();
        req.setInitialPassword("realpw");
        when(userService.signUp("e@x.com", "realpw", "Display")).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(7L, 1L)).thenReturn(member(50L));
        when(roleService.findDefaultRole(7L)).thenReturn(role(99L, "default"));

        UserProvisionResponse resp = service.provision(req, 7L, 100L);

        assertFalse(resp.isMustChangePassword());
        assertNull(resp.getTemporaryPassword());
        assertEquals(List.of("default"), resp.getAssignedRoles());
        verify(userService, never()).update(any());
    }

    @Test
    void provision_withoutPasswordGeneratesTemp() throws Exception {
        UserProvisionRequest req = req();
        when(userService.signUp(eq("e@x.com"), anyString(), eq("Display"))).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(7L, 1L)).thenReturn(member(50L));
        when(roleService.findDefaultRole(7L)).thenReturn(null);

        UserProvisionResponse resp = service.provision(req, 7L, 100L);

        assertTrue(resp.isMustChangePassword());
        assertNotNull(resp.getTemporaryPassword());
        assertEquals(12, resp.getTemporaryPassword().length());
        assertTrue(resp.getAssignedRoles().isEmpty());
        verify(userService).update(argThat(u -> Boolean.TRUE.equals(u.getMustChangePassword())));
    }

    @Test
    void provision_existingUserThrowsBusinessException() throws Exception {
        UserProvisionRequest req = req();
        when(userService.signUp(any(), any(), any())).thenThrow(new RuntimeException("dup"));
        when(userService.findByEmail("e@x.com")).thenReturn(user(99L));
        assertThrows(BusinessException.class, () -> service.provision(req, 7L, 100L));
    }

    @Test
    void provision_signUpFailureWithoutExistingPropagates() throws Exception {
        UserProvisionRequest req = req();
        when(userService.signUp(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        when(userService.findByEmail("e@x.com")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.provision(req, 7L, 100L));
    }

    @Test
    void provision_addMemberAlreadyExistsContinues() throws Exception {
        UserProvisionRequest req = req();
        req.setInitialPassword("p");
        when(userService.signUp(any(), any(), any())).thenReturn(user(1L));
        when(tenantMemberService.addMember(1L, 7L, "active")).thenThrow(new BusinessException("already"));
        when(tenantMemberService.findByTenantIdAndUserId(7L, 1L)).thenReturn(member(50L));
        when(roleService.findDefaultRole(7L)).thenReturn(role(99L, "default"));

        UserProvisionResponse resp = service.provision(req, 7L, 100L);
        assertEquals(List.of("default"), resp.getAssignedRoles());
    }

    @Test
    void provision_explicitRoleCodesFiltersByTenantRoles() throws Exception {
        UserProvisionRequest req = req();
        req.setInitialPassword("p");
        req.setRoleCodes(List.of("admin", "ghost"));
        when(userService.signUp(any(), any(), any())).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(7L, 1L)).thenReturn(member(50L));
        when(roleService.findByTenantId(7L)).thenReturn(List.of(role(1L, "admin"), role(2L, "user")));

        UserProvisionResponse resp = service.provision(req, 7L, 100L);
        assertEquals(List.of("admin"), resp.getAssignedRoles());
        verify(roleService).assignRoleToMember(50L, 1L, 7L);
        verify(roleService, never()).assignRoleToMember(eq(50L), eq(2L), anyLong());
    }

    @Test
    void provision_nullMemberIdSkipsRoleAssignment() throws Exception {
        UserProvisionRequest req = req();
        req.setInitialPassword("p");
        when(userService.signUp(any(), any(), any())).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(7L, 1L)).thenReturn(null);

        UserProvisionResponse resp = service.provision(req, 7L, 100L);
        assertTrue(resp.getAssignedRoles().isEmpty());
        verify(roleService, never()).assignRoleToMember(any(), any(), any());
    }

    @Test
    void provision_emptyRoleCodesUsesDefaultRole() throws Exception {
        UserProvisionRequest req = req();
        req.setInitialPassword("p");
        req.setRoleCodes(List.of());
        when(userService.signUp(any(), any(), any())).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(7L, 1L)).thenReturn(member(50L));
        when(roleService.findDefaultRole(7L)).thenReturn(role(9L, "viewer"));
        UserProvisionResponse resp = service.provision(req, 7L, 100L);
        assertEquals(List.of("viewer"), resp.getAssignedRoles());
    }
}
